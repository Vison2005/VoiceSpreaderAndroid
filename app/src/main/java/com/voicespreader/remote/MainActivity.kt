package com.voicespreader.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var pairingStatus: TextView
    private lateinit var streamStatus: TextView
    private lateinit var connectionBadge: TextView
    private lateinit var levelText: TextView
    private lateinit var microphoneLevel: LinearProgressIndicator
    private lateinit var disconnectButton: Button
    private lateinit var microphoneButton: MaterialButton
    private lateinit var backgroundSettingsButton: Button
    private lateinit var codeInput: EditText

    private val discovery = PairingDiscovery()
    private var pendingPairing: PairingInfo? = null
    private var pendingLocateFallback = false
    private var streamingService: MicrophoneStreamingService? = null
    private var serviceBound = false
    private var smoothedLevelDbfs = -60.0
    private var latestSnapshot = MicrophoneStreamingService.Snapshot()

    private val serviceListener = MicrophoneStreamingService.Listener { snapshot ->
        runOnUiThread { renderSnapshot(snapshot) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            streamingService =
                (binder as? MicrophoneStreamingService.LocalBinder)?.getService()
            serviceBound = streamingService != null
            streamingService?.registerListener(serviceListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            serviceBound = false
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchScanner()
        } else {
            pairingStatus.setText(R.string.permission_camera_required)
        }
    }

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            pairingStatus.setText(R.string.permission_microphone_required)
            return@registerForActivityResult
        }
        streamingService?.setMicrophoneEnabled(true)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val payload = result.data?.getStringExtra(ScannerActivity.EXTRA_PAYLOAD)
        val pairing = payload?.let(PairingInfo::fromQrPayload)
        if (pairing == null) {
            pairingStatus.setText(R.string.qr_invalid)
        } else {
            connect(pairing, allowLocateFallback = true)
        }
    }

    @SuppressLint("ImplicitSamInstance")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applySafeDrawingInsets()

        pairingStatus = findViewById(R.id.pairingStatus)
        streamStatus = findViewById(R.id.streamStatus)
        connectionBadge = findViewById(R.id.connectionBadge)
        levelText = findViewById(R.id.levelText)
        microphoneLevel = findViewById(R.id.microphoneLevel)
        microphoneButton = findViewById(R.id.microphoneButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        backgroundSettingsButton = findViewById(R.id.backgroundSettingsButton)
        codeInput = findViewById(R.id.codeInput)

        connectionBadge.setOnClickListener {
            if (connectionBadge.isEnabled) disconnectFromComputer()
        }

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            if (hasPermission(Manifest.permission.CAMERA)) {
                launchScanner()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        findViewById<Button>(R.id.codeButton).setOnClickListener {
            val code = codeInput.text.toString()
            if (code.length != 6) {
                pairingStatus.setText(R.string.code_invalid)
                return@setOnClickListener
            }
            pairingStatus.setText(R.string.searching_computer)
            setConnectionBadge(
                getString(R.string.badge_searching),
                R.drawable.badge_connecting,
                R.color.accent,
            )
            discovery.discover(
                code,
                onResult = { pairing -> runOnUiThread { connect(pairing) } },
                onError = { message ->
                    runOnUiThread {
                        pairingStatus.text = getString(R.string.discovery_failed, message)
                        setConnectionBadge(
                            getString(R.string.badge_error),
                            R.drawable.badge_error,
                            R.color.error,
                        )
                    }
                },
            )
        }
        disconnectButton.setOnClickListener { disconnectFromComputer() }
        microphoneButton.setOnClickListener { toggleMicrophone() }
        backgroundSettingsButton.setOnClickListener { requestBackgroundProtection() }
        updateBackgroundProtectionButton()
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, MicrophoneStreamingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        if (serviceBound) {
            streamingService?.unregisterListener(serviceListener)
            unbindService(serviceConnection)
            serviceBound = false
            streamingService = null
        }
        super.onStop()
    }

    private fun connect(pairing: PairingInfo, allowLocateFallback: Boolean = false) {
        pendingPairing = pairing
        pendingLocateFallback = allowLocateFallback
        startStreamingService(pairing, allowLocateFallback)
    }

    private fun startStreamingService(pairing: PairingInfo, allowLocateFallback: Boolean) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        pendingPairing = null
        pendingLocateFallback = false
        requestNotificationPermissionIfNeeded()
        pairingStatus.text = getString(R.string.connecting_endpoint, pairing.host, pairing.port)
        setConnectionBadge(
            getString(R.string.badge_connecting),
            R.drawable.badge_connecting,
            R.color.accent,
        )
        disconnectButton.isEnabled = true
        runCatching {
            ContextCompat.startForegroundService(
                this,
                MicrophoneStreamingService.createStartIntent(
                    this,
                    pairing,
                    allowLocateFallback,
                ),
            )
        }.onFailure {
            renderSnapshot(
                MicrophoneStreamingService.Snapshot(
                    state = MicrophoneStreamingService.State.ERROR,
                    message = getString(R.string.background_start_failed, it.message),
                ),
            )
        }
    }

    private fun renderSnapshot(snapshot: MicrophoneStreamingService.Snapshot) {
        latestSnapshot = snapshot
        pairingStatus.text = snapshot.message
        when (snapshot.state) {
            MicrophoneStreamingService.State.IDLE -> {
                setConnectionBadge(
                    getString(R.string.badge_disconnected),
                    R.drawable.badge_neutral,
                    R.color.text_secondary,
                )
                streamStatus.setText(R.string.stream_waiting)
                disconnectButton.isEnabled = false
                connectionBadge.isEnabled = false
                connectionBadge.contentDescription = getString(R.string.badge_disconnected)
                microphoneButton.isEnabled = false
                renderMicrophoneButton(false)
                updateLevel(-120.0, immediate = true)
            }

            MicrophoneStreamingService.State.CONNECTING -> {
                setConnectionBadge(
                    getString(R.string.badge_connecting),
                    R.drawable.badge_connecting,
                    R.color.accent,
                )
                streamStatus.setText(R.string.stream_preparing)
                disconnectButton.isEnabled = true
                connectionBadge.isEnabled = true
                connectionBadge.contentDescription = getString(R.string.cancel_connection)
                microphoneButton.isEnabled = false
                renderMicrophoneButton(false)
                updateLevel(-120.0)
            }

            MicrophoneStreamingService.State.CONNECTED -> {
                setConnectionBadge(
                    getString(R.string.badge_connected),
                    R.drawable.badge_connected,
                    R.color.success,
                )
                streamStatus.setText(R.string.stream_ready)
                disconnectButton.isEnabled = true
                connectionBadge.isEnabled = true
                connectionBadge.contentDescription = getString(R.string.disconnect_from_badge)
                microphoneButton.isEnabled = true
                renderMicrophoneButton(false)
                updateLevel(-120.0, immediate = true)
            }

            MicrophoneStreamingService.State.STREAMING -> {
                setConnectionBadge(
                    getString(R.string.badge_connected),
                    R.drawable.badge_connected,
                    R.color.success,
                )
                streamStatus.setText(R.string.stream_active)
                disconnectButton.isEnabled = true
                connectionBadge.isEnabled = true
                connectionBadge.contentDescription = getString(R.string.disconnect_from_badge)
                microphoneButton.isEnabled = true
                renderMicrophoneButton(true)
                updateLevel(snapshot.levelDbfs)
            }

            MicrophoneStreamingService.State.ERROR -> {
                setConnectionBadge(
                    getString(R.string.badge_error),
                    R.drawable.badge_error,
                    R.color.error,
                )
                streamStatus.setText(R.string.stream_stopped)
                disconnectButton.isEnabled = false
                connectionBadge.isEnabled = false
                connectionBadge.contentDescription = getString(R.string.badge_error)
                microphoneButton.isEnabled = false
                renderMicrophoneButton(false)
                updateLevel(-120.0, immediate = true)
            }
        }
    }

    private fun toggleMicrophone() {
        val service = streamingService ?: return
        if (latestSnapshot.state == MicrophoneStreamingService.State.STREAMING) {
            service.setMicrophoneEnabled(false)
            return
        }
        if (latestSnapshot.state != MicrophoneStreamingService.State.CONNECTED) return
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            service.setMicrophoneEnabled(true)
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun renderMicrophoneButton(enabled: Boolean) {
        microphoneButton.text = getString(
            if (enabled) R.string.disable_microphone else R.string.enable_microphone,
        )
        microphoneButton.setIconResource(
            if (enabled) R.drawable.ic_microphone_off else R.drawable.ic_microphone,
        )
        microphoneButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.primary else R.color.surface_variant,
            ),
        )
        microphoneButton.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) android.R.color.white else R.color.primary,
            ),
        )
        microphoneButton.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (enabled) android.R.color.white else R.color.accent,
            ),
        )
        microphoneButton.isChecked = enabled
    }

    private fun updateLevel(dbfs: Double, immediate: Boolean = false) {
        val target = if (dbfs <= -119.0) -60.0 else dbfs.coerceIn(-60.0, 0.0)
        smoothedLevelDbfs = if (immediate) {
            target
        } else {
            // 上升更快、回落更慢，既保留瞬态响应又避免电平条跳动。
            val coefficient = if (target > smoothedLevelDbfs) 0.58 else 0.18
            smoothedLevelDbfs + (target - smoothedLevelDbfs) * coefficient
        }

        levelText.text = if (dbfs <= -119.0 && smoothedLevelDbfs <= -59.5) {
            getString(R.string.level_silent)
        } else {
            getString(R.string.level_value, smoothedLevelDbfs)
        }
        microphoneLevel.setProgressCompat(
            (smoothedLevelDbfs + 60.0).coerceIn(0.0, 60.0).roundToInt(),
            !immediate,
        )
    }

    @SuppressLint("ImplicitSamInstance")
    private fun disconnectFromComputer() {
        val service = streamingService
        if (service != null) {
            service.stopStreaming(getString(R.string.user_disconnected))
        } else {
            stopService(Intent(this, MicrophoneStreamingService::class.java))
        }
        renderSnapshot(
            MicrophoneStreamingService.Snapshot(
                message = getString(R.string.user_disconnected),
            ),
        )
    }

    private fun setConnectionBadge(text: String, background: Int, color: Int) {
        connectionBadge.text = text
        connectionBadge.setBackgroundResource(background)
        connectionBadge.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun launchScanner() {
        scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBackgroundProtection() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            pairingStatus.setText(R.string.background_protection_enabled)
            updateBackgroundProtectionButton()
            return
        }
        val directRequest = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:$packageName".toUri(),
        )
        runCatching { startActivity(directRequest) }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun updateBackgroundProtectionButton() {
        val powerManager = getSystemService(PowerManager::class.java)
        backgroundSettingsButton.text =
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                getString(R.string.background_protection_button_enabled)
            } else {
                getString(R.string.background_protection_button)
            }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun applySafeDrawingInsets() {
        val root = findViewById<android.view.View>(R.id.mainRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = safe.left,
                top = safe.top,
                right = safe.right,
                bottom = safe.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        if (::backgroundSettingsButton.isInitialized) updateBackgroundProtectionButton()
        val pairing = pendingPairing
        if (pairing != null) {
            startStreamingService(pairing, pendingLocateFallback)
        }
    }

    override fun onDestroy() {
        discovery.close()
        super.onDestroy()
    }
}
