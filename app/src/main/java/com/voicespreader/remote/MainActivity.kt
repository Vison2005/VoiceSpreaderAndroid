package com.voicespreader.remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.DataOutputStream
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var pairingStatus: TextView
    private lateinit var streamStatus: TextView
    private lateinit var levelText: TextView
    private lateinit var microphoneLevel: ProgressBar
    private lateinit var disconnectButton: Button
    private lateinit var codeInput: EditText

    private val discovery = PairingDiscovery()
    private val client = PairingClient()
    private lateinit var streamer: AudioStreamer
    private var pendingPairing: PairingInfo? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            pairingStatus.text = "需要相机和麦克风权限才能扫码并测量"
            return@registerForActivityResult
        }
        val pairing = pendingPairing
        if (pairing != null
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            connect(pairing)
        }
    }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val payload = result.data?.getStringExtra(ScannerActivity.EXTRA_PAYLOAD)
        val pairing = payload?.let(PairingInfo::fromQrPayload)
        if (pairing == null) {
            pairingStatus.text = "二维码不是 VoiceSpreader 配对信息"
        } else {
            connect(pairing)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        streamer = AudioStreamer(this)
        pairingStatus = findViewById(R.id.pairingStatus)
        streamStatus = findViewById(R.id.streamStatus)
        levelText = findViewById(R.id.levelText)
        microphoneLevel = findViewById(R.id.microphoneLevel)
        disconnectButton = findViewById(R.id.disconnectButton)
        codeInput = findViewById(R.id.codeInput)

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            if (ensurePermission(Manifest.permission.CAMERA)) {
                scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
            }
        }
        findViewById<Button>(R.id.codeButton).setOnClickListener {
            val code = codeInput.text.toString()
            if (code.length != 6) {
                pairingStatus.text = "请输入电脑上显示的六位配对码"
                return@setOnClickListener
            }
            pairingStatus.text = "正在局域网中查找电脑…"
            discovery.discover(
                code,
                onResult = { pairing -> runOnUiThread { connect(pairing) } },
                onError = { message ->
                    runOnUiThread { pairingStatus.text = "查找失败：$message" }
                },
            )
        }
        disconnectButton.setOnClickListener { disconnect("已主动断开") }
        requestInitialPermissions()
    }

    private fun connect(pairing: PairingInfo) {
        if (!ensurePermission(Manifest.permission.RECORD_AUDIO)) {
            pendingPairing = pairing
            return
        }
        pendingPairing = null
        pairingStatus.text = "正在连接 ${pairing.host}:${pairing.port}…"
        client.connect(
            pairing,
            onConnected = { output -> runOnUiThread { beginStreaming(output, pairing) } },
            onError = { message ->
                runOnUiThread {
                    pairingStatus.text = "连接失败：$message"
                    setDisconnectedUi()
                }
            },
        )
    }

    private fun beginStreaming(output: DataOutputStream, pairing: PairingInfo) {
        pairingStatus.text = "已配对 ${pairing.host}:${pairing.port}"
        streamStatus.text = "正在回传 48 kHz / PCM16 / 单声道原始采样"
        disconnectButton.isEnabled = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        streamer.start(
            output,
            onLevel = { dbfs -> runOnUiThread { updateLevel(dbfs) } },
            onError = { message -> runOnUiThread { disconnect("回传失败：$message") } },
        )
    }

    private fun updateLevel(dbfs: Double) {
        levelText.text = if (dbfs <= -119.0) "−∞ dBFS" else "%.1f dBFS".format(dbfs)
        microphoneLevel.progress = (dbfs + 60.0).coerceIn(0.0, 60.0).roundToInt()
    }

    private fun disconnect(reason: String) {
        streamer.stop()
        client.disconnect()
        pairingStatus.text = reason
        setDisconnectedUi()
    }

    private fun setDisconnectedUi() {
        streamStatus.text = "等待连接"
        disconnectButton.isEnabled = false
        microphoneLevel.progress = 0
        levelText.text = "−∞ dBFS"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun ensurePermission(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, permission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        permissionLauncher.launch(arrayOf(permission))
        return false
    }

    private fun requestInitialPermissions() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        val pairing = pendingPairing
        if (pairing != null
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            connect(pairing)
        }
    }

    override fun onDestroy() {
        streamer.stop()
        client.close()
        discovery.close()
        super.onDestroy()
    }
}
