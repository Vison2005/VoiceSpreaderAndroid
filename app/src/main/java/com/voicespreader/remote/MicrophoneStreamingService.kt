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
import java.io.DataOutputStream

class MicrophoneStreamingService : Service() {
    enum class State {
        IDLE,
        CONNECTING,
        STREAMING,
        ERROR,
    }

    data class Snapshot(
        val state: State = State.IDLE,
        val message: String = "尚未连接",
        val levelDbfs: Double = -120.0,
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
    private var listener: Listener? = null
    private var snapshot = Snapshot()
    private var connectionGeneration = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        streamer = AudioStreamer(this)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fail("麦克风权限未授予")
            return START_NOT_STICKY
        }

        val foregroundStarted = runCatching {
            startAsForeground("正在连接电脑")
        }.isSuccess
        if (!foregroundStarted) {
            fail("系统未允许启动后台麦克风服务")
            return START_NOT_STICKY
        }
        acquireConnectionLocks()
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
        streamer.stop()
        client.disconnect()
        releaseConnectionLocks()
        publish(Snapshot(State.IDLE, reason))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startConnection(pairing: PairingInfo, allowLocateFallback: Boolean) {
        val generation = ++connectionGeneration
        streamer.stop()
        client.disconnect()
        publish(Snapshot(State.CONNECTING, "正在连接 ${pairing.host}:${pairing.port}…"))
        updateNotification("正在连接 ${pairing.host}:${pairing.port}")
        client.connect(
            pairing,
            onConnected = { output ->
                mainHandler.post {
                    if (generation == connectionGeneration) {
                        beginStreaming(output, pairing)
                    }
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

    private fun beginStreaming(output: DataOutputStream, pairing: PairingInfo) {
        publish(
            Snapshot(
                state = State.STREAMING,
                message = "已连接 ${pairing.host}:${pairing.port}",
            ),
        )
        updateNotification("正在向 ${pairing.host}:${pairing.port} 回传麦克风")
        streamer.start(
            output,
            onLevel = { level ->
                mainHandler.post {
                    if (snapshot.state == State.STREAMING) {
                        publish(snapshot.copy(levelDbfs = level))
                    }
                }
            },
            onError = { message -> mainHandler.post { fail("回传失败：$message") } },
        )
    }

    private fun fail(message: String) {
        ++connectionGeneration
        streamer.stop()
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

    private fun startAsForeground(message: String) {
        val foregroundTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
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

    override fun onDestroy() {
        ++connectionGeneration
        streamer.stop()
        client.close()
        discovery.close()
        releaseConnectionLocks()
        super.onDestroy()
    }
}
