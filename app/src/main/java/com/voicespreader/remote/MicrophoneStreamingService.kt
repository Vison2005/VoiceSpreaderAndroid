package com.voicespreader.remote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.io.DataOutputStream
import java.util.UUID

class MicrophoneStreamingService : Service() {
    enum class State {
        IDLE,
        CONNECTING,
        CONNECTED,
        STREAMING,
        ERROR,
    }

    data class Snapshot(
        val state: State = State.IDLE,
        val message: String = "尚未连接",
        val levelDbfs: Double = -120.0,
        val microphoneActive: Boolean = false,
        val playbackActive: Boolean = false,
        val playbackDescription: String = "连接电脑后等待 Windows 端启动",
    )

    fun interface Listener {
        fun onSnapshotChanged(snapshot: Snapshot)
    }

    inner class LocalBinder : Binder() {
        fun getService(): MicrophoneStreamingService = this@MicrophoneStreamingService
    }

    companion object {
        private const val ACTION_START = "com.voicespreader.remote.action.START"
        private const val ACTION_STOP = "com.voicespreader.remote.action.STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_SECRET = "secret"
        private const val EXTRA_ALLOW_LOCATE = "allow_locate"
        private const val CHANNEL_ID = "microphone_streaming"
        private const val NOTIFICATION_ID = 4107

        fun createStartIntent(
            context: Context,
            pairing: PairingInfo,
            allowLocateFallback: Boolean,
        ): Intent = Intent(context, MicrophoneStreamingService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_HOST, pairing.host)
            .putExtra(EXTRA_PORT, pairing.port)
            .putExtra(EXTRA_SESSION, pairing.session)
            .putExtra(EXTRA_SECRET, pairing.secret)
            .putExtra(EXTRA_ALLOW_LOCATE, allowLocateFallback)

        fun createStopIntent(context: Context): Intent =
            Intent(context, MicrophoneStreamingService::class.java).setAction(ACTION_STOP)
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = PairingClient()
    private val discovery = PairingDiscovery()
    private lateinit var streamer: AudioStreamer
    private lateinit var remotePlayer: RemoteAudioPlayer
    private var listener: Listener? = null
    private var snapshot = Snapshot()
    private var connectionGeneration = 0
    @Volatile
    private var microphoneGeneration = 0
    @Volatile
    private var microphoneRequested = false
    @Volatile
    private var playbackRequested = false
    private var streamOutput: DataOutputStream? = null
    private var connectedPairing: PairingInfo? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        streamer = AudioStreamer(this)
        remotePlayer = RemoteAudioPlayer()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming("已主动断开")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        val pairing = PairingInfo(
            host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
            port = intent.getIntExtra(EXTRA_PORT, 0),
            session = intent.getStringExtra(EXTRA_SESSION).orEmpty(),
            secret = intent.getStringExtra(EXTRA_SECRET).orEmpty(),
        )
        if (pairing.host.isBlank() || pairing.port !in 1..65535) {
            fail("配对信息不完整")
            return START_NOT_STICKY
        }
        val foregroundStarted = runCatching {
            startAsForeground("正在连接电脑", microphoneActive = false)
        }.isSuccess
        if (!foregroundStarted) {
            fail("系统未允许启动后台麦克风服务")
            return START_NOT_STICKY
        }
        startConnection(pairing, intent.getBooleanExtra(EXTRA_ALLOW_LOCATE, false))
        return START_NOT_STICKY
    }

    fun registerListener(value: Listener) {
        listener = value
        value.onSnapshotChanged(snapshot)
    }

    fun unregisterListener(value: Listener) {
        if (listener === value) listener = null
    }

    fun currentSnapshot(): Snapshot = snapshot

    fun stopStreaming(reason: String = "已主动断开") {
        ++connectionGeneration
        ++microphoneGeneration
        streamer.stop()
        remotePlayer.stop()
        microphoneRequested = false
        playbackRequested = false
        sendMicrophoneState(false)
        sendPlaybackState(false)
        streamOutput = null
        connectedPairing = null
        client.disconnect()
        releaseConnectionLocks()
        publish(Snapshot(State.IDLE, reason))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startConnection(pairing: PairingInfo, allowLocateFallback: Boolean) {
        val generation = ++connectionGeneration
        ++microphoneGeneration
        streamer.stop()
        remotePlayer.stop()
        microphoneRequested = false
        playbackRequested = false
        releaseConnectionLocks()
        streamOutput = null
        connectedPairing = null
        client.disconnect()
        publish(Snapshot(State.CONNECTING, "正在连接 ${pairing.host}:${pairing.port}…"))
        updateNotification("正在连接 ${pairing.host}:${pairing.port}")
        client.connect(
            pairing,
            stableDeviceId(),
            onConnected = { output ->
                mainHandler.post {
                    if (generation == connectionGeneration) {
                        beginConnected(output, pairing)
                    }
                }
            },
            onMicrophoneCommand = { enabled ->
                mainHandler.post {
                    if (generation == connectionGeneration) {
                        setMicrophoneEnabled(enabled)
                    }
                }
            },
            onPlaybackCommand = { enabled ->
                mainHandler.post {
                    if (generation == connectionGeneration) {
                        setPlaybackEnabled(enabled)
                    }
                }
            },
            onPlaybackFrame = { frame ->
                if (generation == connectionGeneration && playbackRequested) {
                    remotePlayer.enqueue(frame)
                }
            },
            onError = { message ->
                mainHandler.post {
                    if (generation != connectionGeneration) return@post
                    if (allowLocateFallback) {
                        locateAndReconnect(pairing, generation, message)
                    } else {
                        fail("连接失败：$message")
                    }
                }
            },
        )
    }

    private fun locateAndReconnect(
        pairing: PairingInfo,
        generation: Int,
        directError: String,
    ) {
        publish(Snapshot(State.CONNECTING, "二维码地址不可达，正在自动定位电脑…"))
        updateNotification("正在局域网中定位电脑")
        discovery.locate(
            pairing,
            onResult = { located ->
                mainHandler.post {
                    if (generation == connectionGeneration) {
                        startConnection(located, allowLocateFallback = false)
                    }
                }
            },
            onError = { locateError ->
                mainHandler.post {
                    if (generation == connectionGeneration) {
                        fail("连接失败：$directError；自动定位失败：$locateError")
                    }
                }
            },
        )
    }

    private fun beginConnected(output: DataOutputStream, pairing: PairingInfo) {
        ++microphoneGeneration
        microphoneRequested = false
        playbackRequested = false
        streamOutput = output
        connectedPairing = pairing
        publish(
            Snapshot(
                state = State.CONNECTED,
                message = "已连接 ${pairing.host}:${pairing.port}",
            ),
        )
        updateNotification("已连接电脑 · 麦克风未启用")
        sendMicrophoneState(false)
        sendPlaybackState(false)
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        val output = streamOutput ?: return
        if (microphoneRequested == enabled) return
        microphoneRequested = enabled

        val generation = ++microphoneGeneration
        if (!enabled) {
            streamer.stop()
            updateConnectionLocks()
            sendMicrophoneState(false)
            val endpoint = connectedPairing
            publish(
                Snapshot(
                    state = State.CONNECTED,
                    message = endpoint?.let { "已连接 ${it.host}:${it.port}" } ?: "已连接电脑",
                    playbackActive = snapshot.playbackActive,
                    playbackDescription = snapshot.playbackDescription,
                ),
            )
            updateConnectedNotification()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            microphoneRequested = false
            val message = "请先在手机端授予麦克风权限"
            sendControlError(message)
            sendMicrophoneState(false)
            publish(snapshot.copy(state = State.CONNECTED, message = message, microphoneActive = false))
            updateConnectedNotification()
            return
        }

        val foregroundReady = runCatching {
            startAsForeground("正在启用手机麦克风", microphoneActive = true)
        }.isSuccess
        if (!foregroundReady) {
            microphoneRequested = false
            val message = "系统不允许在当前状态启用麦克风，请打开应用后重试"
            sendControlError(message)
            sendMicrophoneState(false)
            publish(snapshot.copy(state = State.CONNECTED, message = message, microphoneActive = false))
            updateConnectedNotification()
            return
        }

        acquireConnectionLocks()
        publish(
            snapshot.copy(
                state = State.CONNECTED,
                message = "正在启用手机麦克风…",
                microphoneActive = false,
            ),
        )
        streamer.start(
            output,
            onStarted = {
                if (generation == microphoneGeneration) {
                    sendMicrophoneState(true)
                    mainHandler.post {
                        if (generation == microphoneGeneration && streamOutput === output) {
                            publish(
                                Snapshot(
                                    state = State.STREAMING,
                                    message = "手机麦克风正在回传",
                                    microphoneActive = true,
                                    playbackActive = snapshot.playbackActive,
                                    playbackDescription = snapshot.playbackDescription,
                                ),
                            )
                            updateConnectedNotification()
                        }
                    }
                }
            },
            onLevel = { level ->
                mainHandler.post {
                    if (generation == microphoneGeneration
                        && snapshot.state == State.STREAMING
                    ) {
                        publish(snapshot.copy(levelDbfs = level))
                    }
                }
            },
            onError = { message ->
                mainHandler.post {
                    if (generation == microphoneGeneration) {
                        ++microphoneGeneration
                        microphoneRequested = false
                        streamer.stop()
                        updateConnectionLocks()
                        sendMicrophoneState(false)
                        sendControlError("回传失败：$message")
                        publish(
                            Snapshot(
                                state = State.CONNECTED,
                                message = "麦克风回传失败：$message",
                                playbackActive = snapshot.playbackActive,
                                playbackDescription = snapshot.playbackDescription,
                            ),
                        )
                        updateConnectedNotification()
                    }
                }
            },
        )
    }

    private fun setPlaybackEnabled(enabled: Boolean) {
        if (playbackRequested == enabled) return
        playbackRequested = enabled
        if (!enabled) {
            remotePlayer.stop()
            sendPlaybackState(false)
            updateConnectionLocks()
            publish(
                snapshot.copy(
                    playbackActive = false,
                    playbackDescription = "Windows 声音播放已停止",
                ),
            )
            updateConnectedNotification()
            return
        }

        acquireConnectionLocks()
        publish(
            snapshot.copy(
                playbackActive = false,
                playbackDescription = "正在等待 Windows 音频数据…",
            ),
        )
        updateConnectedNotification()
        remotePlayer.start(
            onStarted = { sampleRate, channels ->
                if (!playbackRequested) return@start
                sendPlaybackState(true)
                mainHandler.post {
                    if (playbackRequested) {
                        publish(
                            snapshot.copy(
                                playbackActive = true,
                                playbackDescription =
                                    "$sampleRate Hz · PCM16 · ${if (channels == 2) "双声道" else "单声道"} · 正在播放",
                            ),
                        )
                        updateConnectedNotification()
                    }
                }
            },
            onError = { message ->
                mainHandler.post {
                    if (playbackRequested) {
                        playbackRequested = false
                        remotePlayer.stop()
                        sendPlaybackState(false)
                        sendPlaybackError(message)
                        updateConnectionLocks()
                        publish(
                            snapshot.copy(
                                playbackActive = false,
                                playbackDescription = "Windows 声音播放失败：$message",
                            ),
                        )
                        updateConnectedNotification()
                    }
                }
            },
        )
    }

    private fun sendMicrophoneState(enabled: Boolean) {
        val output = streamOutput ?: return
        runCatching {
            synchronized(output) {
                output.writeInt(2)
                output.writeByte(2)
                output.writeByte(if (enabled) 1 else 0)
                output.flush()
            }
        }
    }

    private fun sendControlError(message: String) {
        val output = streamOutput ?: return
        val payload = message.toByteArray(Charsets.UTF_8)
        runCatching {
            synchronized(output) {
                output.writeInt(1 + payload.size)
                output.writeByte(3)
                output.write(payload)
                output.flush()
            }
        }
    }

    private fun sendPlaybackState(enabled: Boolean) {
        val output = streamOutput ?: return
        runCatching {
            synchronized(output) {
                output.writeInt(2)
                output.writeByte(5)
                output.writeByte(if (enabled) 1 else 0)
                output.flush()
            }
        }
    }

    private fun sendPlaybackError(message: String) {
        val output = streamOutput ?: return
        val payload = message.toByteArray(Charsets.UTF_8)
        runCatching {
            synchronized(output) {
                output.writeInt(1 + payload.size)
                output.writeByte(6)
                output.write(payload)
                output.flush()
            }
        }
    }

    private fun fail(message: String) {
        ++connectionGeneration
        ++microphoneGeneration
        streamer.stop()
        remotePlayer.stop()
        microphoneRequested = false
        playbackRequested = false
        streamOutput = null
        connectedPairing = null
        client.disconnect()
        releaseConnectionLocks()
        publish(Snapshot(State.ERROR, message))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish(value: Snapshot) {
        snapshot = value
        listener?.onSnapshotChanged(value)
    }

    private fun startAsForeground(message: String, microphoneActive: Boolean) {
        val foregroundTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (microphoneActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            types
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(message),
            foregroundTypes,
        )
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun updateConnectedNotification() {
        val message = when {
            microphoneRequested && playbackRequested -> "正在双向传输音频"
            microphoneRequested -> "手机麦克风正在回传"
            playbackRequested -> "正在播放 Windows 声音"
            else -> "已连接电脑 · 音频链路未启用"
        }
        startAsForeground(message, microphoneActive = microphoneRequested)
    }

    private fun buildNotification(message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                "断开",
                PendingIntent.getService(
                    this,
                    1,
                    createStopIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun acquireConnectionLocks() {
        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:microphone-stream",
            ).apply {
                setReferenceCounted(false)
                acquire(12L * 60L * 60L * 1000L)
            }
        }
        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$packageName:microphone-stream",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseConnectionLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun updateConnectionLocks() {
        if (microphoneRequested || playbackRequested) {
            acquireConnectionLocks()
        } else {
            releaseConnectionLocks()
        }
    }

    private fun stableDeviceId(): String {
        val preferences = getSharedPreferences("device_identity", Context.MODE_PRIVATE)
        val existing = preferences.getString("device_id", null)
        if (!existing.isNullOrBlank()) return existing

        val generated = UUID.randomUUID().toString()
        preferences.edit { putString("device_id", generated) }
        return generated
    }

    override fun onDestroy() {
        ++connectionGeneration
        ++microphoneGeneration
        streamer.stop()
        remotePlayer.stop()
        microphoneRequested = false
        playbackRequested = false
        streamOutput = null
        connectedPairing = null
        client.close()
        discovery.close()
        releaseConnectionLocks()
        super.onDestroy()
    }
}
