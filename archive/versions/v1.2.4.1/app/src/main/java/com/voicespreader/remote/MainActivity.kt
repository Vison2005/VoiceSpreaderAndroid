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
import android.widget.LinearLayout
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
    private lateinit var playbackStatus: TextView
    private lateinit var connectionBadge: TextView
    private lateinit var levelText: TextView
    private lateinit var microphoneLevel: LinearProgressIndicator
    private lateinit var disconnectButton: Button
    private lateinit var microphoneButton: MaterialButton
    private lateinit var backgroundSettingsButton: Button
    private lateinit var codeInput: EditText
    private lateinit var savedDevicesContainer: LinearLayout
    private lateinit var savedDevicesEmpty: TextView

    private val discovery = PairingDiscovery()
    private lateinit var savedPairingStore: SavedPairingStore
    private var pendingPairing: PairingInfo? = null
    private var pendingLocateFallback = false
    private var streamingService: MicrophoneStreamingService? = null
    private var serviceBound = false
    private var smoothedLevelDbfs = -60.0
    private var latestSnapshot = MicrophoneStreamingService.Snapshot()
    private var lastRememberedPairingKey: String? = null

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
        streamingService?.requestMicrophoneEnabled(true)
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
        playbackStatus = findViewById(R.id.playbackStatus)
        connectionBadge = findViewById(R.id.connectionBadge)
        levelText = findViewById(R.id.levelText)
        microphoneLevel = findViewById(R.id.microphoneLevel)
        microphoneButton = findViewById(R.id.microphoneButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        backgroundSettingsButton = findViewById(R.id.backgroundSettingsButton)
        codeInput = findViewById(R.id.codeInput)
        savedDevicesContainer = findViewById(R.id.savedDevicesContainer)
        savedDevicesEmpty = findViewById(R.id.savedDevicesEmpty)
        savedPairingStore = SavedPairingStore(this)
        renderSavedDevices()

        findViewById<Button>(R.id.touchpadButton).setOnClickListener {
            startActivity(Intent(this, TouchpadActivity::class.java))
        }

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
        if (latestSnapshot.state == MicrophoneStreamingService.State.CONNECTED
            || latestSnapshot.state == MicrophoneStreamingService.State.STREAMING
            || latestSnapshot.state == MicrophoneStreamingService.State.CONNECTING
        ) {
            // Activity 退到桌面时提前刷新一次闹钟，避免部分 ROM 在解绑界面后立即回收服务。
            MicrophoneStreamingService.scheduleWatchdog(applicationContext)
        }
        if (serviceBound) {
            streamingService?.unregisterListener(serviceListener)
            unbindService(serviceConnection)
            serviceBound = false
            streamingService = null
        }
        super.onStop()
    }

    private fun connect(pairing: PairingInfo, allowLocateFallback: Boolean = false) {
        if (latestSnapshot.state == MicrophoneStreamingService.State.CONNECTING) return
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
        val previousSnapshot = latestSnapshot
        latestSnapshot = snapshot
        var savedDevicesChanged = previousSnapshot.state != snapshot.state
            || previousSnapshot.pairing?.session != snapshot.pairing?.session
        val pairing = snapshot.pairing
        if ((snapshot.state == MicrophoneStreamingService.State.CONNECTED
                    || snapshot.state == MicrophoneStreamingService.State.STREAMING)
            && pairing != null
        ) {
            val key = "${pairing.session}|${pairing.host}|${pairing.port}"
            if (key != lastRememberedPairingKey) {
                lastRememberedPairingKey = key
                savedPairingStore.remember(pairing)
                savedDevicesChanged = true
            }
        }
        if (savedDevicesChanged) {
            renderSavedDevices()
        }
        pairingStatus.text = snapshot.message
        playbackStatus.text = snapshot.playbackDescription
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
                microphoneButton.isEnabled = !snapshot.microphoneRequestPending
                renderMicrophoneButton(snapshot.microphoneActive)
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
                microphoneButton.isEnabled = !snapshot.microphoneRequestPending
                renderMicrophoneButton(snapshot.microphoneActive)
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
        if (latestSnapshot.microphoneRequestPending) return
        if (latestSnapshot.microphoneActive) {
            service.requestMicrophoneEnabled(false)
            return
        }
        if (latestSnapshot.state != MicrophoneStreamingService.State.CONNECTED) return
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            service.requestMicrophoneEnabled(true)
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun renderSavedDevices() {
        if (!::savedDevicesContainer.isInitialized || !::savedPairingStore.isInitialized) return
        val devices = savedPairingStore.list()
        val density = resources.displayMetrics.density
        val rowGap = (8f * density).roundToInt()
        val rowPadding = (14f * density).roundToInt()
        savedDevicesContainer.removeAllViews()
        savedDevicesEmpty.visibility = if (devices.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        devices.forEach { saved ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.saved_device_background)
                setPadding(rowPadding, rowPadding, rowPadding, rowPadding)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = rowGap
                }
            }
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val name = TextView(this).apply {
                text = saved.name
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val address = TextView(this).apply {
                text = getString(R.string.saved_device_address, saved.pairing.host, saved.pairing.port)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 12f
            }
            details.addView(name)
            details.addView(address)

            val sameSession = latestSnapshot.pairing?.session
                ?.equals(saved.pairing.session, ignoreCase = true) == true
            val isConnected = sameSession && (
                latestSnapshot.state == MicrophoneStreamingService.State.CONNECTED
                    || latestSnapshot.state == MicrophoneStreamingService.State.STREAMING
                )
            val isConnecting = sameSession
                && latestSnapshot.state == MicrophoneStreamingService.State.CONNECTING

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = rowGap
                }
            }
            val connectButton = MaterialButton(this).apply {
                text = getString(
                    when {
                        isConnected -> R.string.saved_connected
                        isConnecting -> R.string.saved_connecting
                        else -> R.string.saved_connect
                    },
                )
                isAllCaps = false
                minWidth = (84f * density).roundToInt()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                isEnabled = !isConnected && !isConnecting
                setOnClickListener { connect(saved.pairing, allowLocateFallback = true) }
            }
            val forgetButton = MaterialButton(this).apply {
                text = getString(R.string.saved_forget)
                isAllCaps = false
                minWidth = (84f * density).roundToInt()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = (8f * density).roundToInt()
                }
                setOnClickListener {
                    savedPairingStore.forget(saved.pairing.session)
                    renderSavedDevices()
                }
            }
            row.addView(
                details,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            actions.addView(connectButton)
            actions.addView(forgetButton)
            row.addView(actions)
            savedDevicesContainer.addView(row)
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
