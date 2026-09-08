package com.voicespreader.remote

import android.Manifest
import android.app.AlarmManager
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.util.concurrent.Executors
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
        val microphoneRequestPending: Boolean = false,
        val playbackActive: Boolean = false,
        val playbackDescription: String = "连接电脑后等待 Windows 端启动",
        val pairing: PairingInfo? = null,
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
        const val ACTION_WATCHDOG = "com.voicespreader.remote.action.WATCHDOG"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_SECRET = "secret"
        private const val EXTRA_ALLOW_LOCATE = "allow_locate"
        private const val CHANNEL_ID = "microphone_streaming"
        private const val NOTIFICATION_ID = 4107
        private const val MICROPHONE_REQUEST_TIMEOUT_MILLISECONDS = 6_000L
        private const val RECONNECT_PORT = 39742
        private const val RECONNECT_PACKET_BYTES = 160
        internal const val SERVICE_PREFERENCES = "streaming_service_state"
        internal const val KEY_ACTIVE = "active"
        internal const val KEY_HOST = "host"
        internal const val KEY_PORT = "port"
        internal const val KEY_SESSION = "session"
        internal const val KEY_SECRET = "secret"
        private const val WATCHDOG_REQUEST_CODE = 41_08
        private const val WATCHDOG_INTERVAL_MILLISECONDS = 15_000L

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

        fun scheduleWatchdog(context: Context, delayMilliseconds: Long = WATCHDOG_INTERVAL_MILLISECONDS) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, ConnectionWatchdogReceiver::class.java)
                .setAction(ACTION_WATCHDOG)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMilliseconds,
                pendingIntent,
            )
        }

        fun cancelWatchdog(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, ConnectionWatchdogReceiver::class.java)
                .setAction(ACTION_WATCHDOG)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = PairingClient()
    private val discovery = PairingDiscovery()
    private val reconnectExecutor = Executors.newSingleThreadExecutor()
    private lateinit var streamer: AudioStreamer
    private lateinit var remotePlayer: RemoteAudioPlayer
    private lateinit var savedPairingStore: SavedPairingStore
    private var listener: Listener? = null
    private var snapshot = Snapshot()
    private var connectionGeneration = 0
    @Volatile
    private var microphoneGeneration = 0
    private var microphoneRequestGeneration = 0
    private var microphoneRequestId = 0L
    private var pendingMicrophoneEnabled: Boolean? = null
    private var pendingMicrophoneRequestId = 0L
    private var lastMicrophoneCommandId = 0L
    // 所有来自手机界面的启停操作都要等到上一条请求完成，避免双击产生交错指令。
    private var microphoneActionInFlight = false
    @Volatile
    private var microphoneRequested = false
    @Volatile
    private var playbackRequested = false
    private var streamOutput: DataOutputStream? = null
    private var connectedPairing: PairingInfo? = null
    private var reconnectTarget: PairingInfo? = null
    private var connectionWasEstablished = false
    private var waitingForReconnect = false
    @Volatile
    private var reconnectListenerRunning = false
    private var reconnectSocket: DatagramSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val servicePreferences by lazy {
        getSharedPreferences(SERVICE_PREFERENCES, Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        streamer = AudioStreamer(this)
        remotePlayer = RemoteAudioPlayer()
        savedPairingStore = SavedPairingStore(this)
        createNotificationChannel()
        startReconnectListener()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming("已主动断开")
            return START_NOT_STICKY
        }
        // 系统回收进程后以 null Intent 重建 START_STICKY 服务。恢复上次连接目标，
        // 这样回到桌面、锁屏或厂商短暂回收进程时，连接不会永久丢失。
        val isWatchdog = intent?.action == ACTION_WATCHDOG
        val isSystemRestart = intent == null || isWatchdog
        if (isWatchdog && (reconnectTarget != null || streamOutput != null)) {
            // 连接等待期间如果监听线程被 ROM 暂停或异常退出，下一次看门狗同时恢复监听。
            if (!reconnectListenerRunning) {
                startReconnectListener()
            }
            scheduleWatchdog()
            return START_STICKY
        }
        if (!isSystemRestart && intent?.action != ACTION_START) return START_NOT_STICKY

        val pairing = if (isSystemRestart) {
            loadActivePairing()
        } else {
            PairingInfo(
                host = intent?.getStringExtra(EXTRA_HOST).orEmpty(),
                port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0,
                session = intent?.getStringExtra(EXTRA_SESSION).orEmpty(),
                secret = intent?.getStringExtra(EXTRA_SECRET).orEmpty(),
            )
        }
        if (pairing == null) return START_NOT_STICKY
        if (pairing.host.isBlank() || pairing.port !in 1..65535) {
            fail("配对信息不完整")
            return START_NOT_STICKY
        }
        persistActivePairing(pairing)
        reconnectTarget = pairing
        waitingForReconnect = false
        val foregroundStarted = runCatching {
            startAsForeground("正在连接电脑", microphoneActive = false)
        }.isSuccess
        if (!foregroundStarted) {
            fail("系统未允许启动后台麦克风服务")
            return START_NOT_STICKY
        }
        scheduleWatchdog()
        if (!reconnectListenerRunning) {
            startReconnectListener()
        }
        startConnection(
            pairing,
            allowLocateFallback = intent?.getBooleanExtra(EXTRA_ALLOW_LOCATE, false) ?: false,
            keepAliveOnFailure = isSystemRestart,
        )
        return START_STICKY
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
        FeatureSessionRegistry.clear()
        ++connectionGeneration
        ++microphoneGeneration
        ++microphoneRequestGeneration
        pendingMicrophoneEnabled = null
        pendingMicrophoneRequestId = 0L
        lastMicrophoneCommandId = 0L
        microphoneActionInFlight = false
        streamer.stop()
        remotePlayer.stop()
        microphoneRequested = false
        playbackRequested = false
        sendMicrophoneState(false)
        sendPlaybackState(false)
        sendDisconnectNotice()
        streamOutput = null
        connectedPairing = null
        reconnectTarget = null
        connectionWasEstablished = false
        waitingForReconnect = false
        clearActivePairing()
        reconnectListenerRunning = false
        synchronized(this) { reconnectSocket?.close() }
        client.disconnect()
        releaseConnectionLocks()
        cancelWatchdog()
        publish(Snapshot(State.IDLE, reason))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startConnection(
        pairing: PairingInfo,
        allowLocateFallback: Boolean,
        keepAliveOnFailure: Boolean = false,
    ) {
        FeatureSessionRegistry.clear()
        persistActivePairing(pairing)
        // 切换到另一台已保存电脑前先发送明确的断开帧，旧电脑不会把主动切换误判为网络故障并广播重连。
        if (streamOutput != null) {
            sendDisconnectNotice()
        }
        val generation = ++connectionGeneration
        ++microphoneGeneration
        ++microphoneRequestGeneration
        pendingMicrophoneEnabled = null
        pendingMicrophoneRequestId = 0L
        lastMicrophoneCommandId = 0L
        microphoneActionInFlight = false
        streamer.stop()
        remotePlayer.stop()
        microphoneRequested = false
        playbackRequested = false
        releaseConnectionLocks()
        streamOutput = null
        connectedPairing = null
        reconnectTarget = pairing
        connectionWasEstablished = false
        waitingForReconnect = false
        client.disconnect()
        publish(
            Snapshot(
                state = State.CONNECTING,
                message = "正在连接 ${pairing.host}:${pairing.port}…",
                pairing = pairing,
            ),
        )
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
            onMicrophoneCommand = { enabled, commandId ->
                mainHandler.post {
                    if (generation == connectionGeneration) {
                        setMicrophoneEnabled(enabled, commandId)
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
            onRemoteDisconnect = {
                mainHandler.post {
                    if (generation == connectionGeneration) {
                        stopStreaming("电脑端已主动断开")
                    }
                }
            },
            onError = { message ->
                mainHandler.post {
                    if (generation != connectionGeneration) return@post
                    if (allowLocateFallback && !connectionWasEstablished) {
                        locateAndReconnect(pairing, generation, message)
                    } else if (connectionWasEstablished || keepAliveOnFailure) {
                        enterReconnectWait(pairing, "电脑连接中断：$message")
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
        publish(
            Snapshot(
                state = State.CONNECTING,
                message = "二维码地址不可达，正在自动定位电脑…",
                pairing = pairing,
            ),
        )
        updateNotification("正在局域网中定位电脑")
        discovery.locate(
            pairing,
            onResult = { located ->
                mainHandler.post {
                    if (generation == connectionGeneration) {
                        startConnection(
                            located,
                            allowLocateFallback = false,
                            keepAliveOnFailure = true,
                        )
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
        ++microphoneRequestGeneration
        pendingMicrophoneEnabled = null
        pendingMicrophoneRequestId = 0L
        lastMicrophoneCommandId = 0L
        microphoneActionInFlight = false
        microphoneRequested = false
        playbackRequested = false
        streamOutput = output
        connectedPairing = pairing
        reconnectTarget = pairing
        FeatureSessionRegistry.pairing = pairing
        FeatureSessionRegistry.deviceId = stableDeviceId()
        FeatureSessionRegistry.onFrame = { type, payload ->
            mainHandler.post { handleFeatureFrame(type, payload) }
        }
        FeatureSessionRegistry.ensureConnected(this)
        persistActivePairing(pairing)
        connectionWasEstablished = true
        waitingForReconnect = false
        publish(
            Snapshot(
                state = State.CONNECTED,
                message = "已连接 ${pairing.host}:${pairing.port}",
                pairing = pairing,
            ),
        )
        updateNotification("已连接电脑 · 麦克风未启用")
        sendMicrophoneState(false)
        sendPlaybackState(false)
    }

    fun requestMicrophoneEnabled(enabled: Boolean) {
        if (streamOutput == null) return
        if (microphoneActionInFlight || snapshot.microphoneRequestPending) return

        val requestGeneration = ++microphoneRequestGeneration
        val requestId = ++microphoneRequestId
        microphoneActionInFlight = true
        if (!enabled) {
            microphoneRequested = false
        }
        pendingMicrophoneEnabled = enabled
        pendingMicrophoneRequestId = requestId
        val action = if (enabled) "启用" else "停止"
        if (!enabled) {
            // 停止必须先在手机本地生效，避免等待 Windows 回包时仍持续占用麦克风。
            microphoneRequested = false
            ++microphoneGeneration
            streamer.stop()
            updateConnectionLocks()
            sendMicrophoneState(false)
        }
        publish(
            snapshot.copy(
                state = if (!enabled) State.CONNECTED else snapshot.state,
                message = "正在请求电脑${action}手机麦克风…",
                microphoneActive = if (!enabled) false else snapshot.microphoneActive,
                microphoneRequestPending = true,
            ),
        )
        if (!sendMicrophoneRequest(enabled, requestId)) {
            microphoneActionInFlight = false
            pendingMicrophoneEnabled = null
            pendingMicrophoneRequestId = 0L
            publish(
                snapshot.copy(
                    message = "无法向电脑发送麦克风请求",
                    microphoneRequestPending = false,
                ),
            )
            return
        }

        mainHandler.postDelayed(
            {
                if (requestGeneration == microphoneRequestGeneration
                    && snapshot.microphoneRequestPending
                ) {
                    microphoneActionInFlight = false
                    pendingMicrophoneEnabled = null
                    pendingMicrophoneRequestId = 0L
                    publish(
                        snapshot.copy(
                            message = "电脑未确认麦克风请求，请检查 VB-CABLE 路由",
                            microphoneRequestPending = false,
                        ),
                    )
                }
            },
            MICROPHONE_REQUEST_TIMEOUT_MILLISECONDS,
        )
    }

    fun setMicrophoneEnabled(enabled: Boolean, commandId: Long = 0L) {
        val output = streamOutput ?: return
        if (commandId > 0L && commandId <= lastMicrophoneCommandId) {
            // 重复或迟到的命令只回报当前状态，不能再次启动或停止录音。
            sendMicrophoneState(snapshot.microphoneActive, commandId)
            return
        }
        if (commandId > 0L) {
            lastMicrophoneCommandId = commandId
        }
        ++microphoneRequestGeneration
        val phoneRequest = pendingMicrophoneEnabled
        pendingMicrophoneEnabled = null
        pendingMicrophoneRequestId = 0L
        if (!enabled) {
            microphoneActionInFlight = false
            microphoneRequested = false
            ++microphoneGeneration
            streamer.stop()
            updateConnectionLocks()
            sendMicrophoneState(false, commandId)
            val endpoint = connectedPairing
            publish(
                Snapshot(
                    state = State.CONNECTED,
                    message = endpoint?.let { "已连接 ${it.host}:${it.port}" } ?: "已连接电脑",
                    microphoneRequestPending = false,
                    playbackActive = snapshot.playbackActive,
                    playbackDescription = snapshot.playbackDescription,
                    pairing = connectedPairing,
                ),
            )
            updateConnectedNotification()
            return
        }
        if (microphoneRequested == enabled) {
            microphoneActionInFlight = false
            // 重复命令也必须回报实际状态，用于恢复丢失的启停确认。
            sendMicrophoneState(snapshot.microphoneActive, commandId)
            val endpoint = connectedPairing
            val message = when {
                phoneRequest == true && !enabled -> "电脑未能启用手机麦克风"
                snapshot.microphoneActive -> "手机麦克风正在回传"
                enabled -> "正在启用手机麦克风…"
                else -> endpoint?.let { "已连接 ${it.host}:${it.port}" } ?: "已连接电脑"
            }
            publish(
                snapshot.copy(
                    message = message,
                    microphoneRequestPending = enabled && !snapshot.microphoneActive,
                ),
            )
            return
        }
        microphoneRequested = enabled

        val generation = ++microphoneGeneration
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            microphoneRequested = false
            microphoneActionInFlight = false
            val message = "请先在手机端授予麦克风权限"
            sendControlError(message)
            sendMicrophoneState(false, commandId)
            publish(
                snapshot.copy(
                    state = State.CONNECTED,
                    message = message,
                    microphoneActive = false,
                    microphoneRequestPending = false,
                ),
            )
            updateConnectedNotification()
            return
        }

        val foregroundReady = runCatching {
            startAsForeground("正在启用手机麦克风", microphoneActive = true)
        }.isSuccess
        if (!foregroundReady) {
            microphoneRequested = false
            microphoneActionInFlight = false
            val message = "系统不允许在当前状态启用麦克风，请打开应用后重试"
            sendControlError(message)
            sendMicrophoneState(false, commandId)
            publish(
                snapshot.copy(
                    state = State.CONNECTED,
                    message = message,
                    microphoneActive = false,
                    microphoneRequestPending = false,
                ),
            )
            updateConnectedNotification()
            return
        }

        acquireConnectionLocks()
        publish(
            snapshot.copy(
                state = State.CONNECTED,
                message = "正在启用手机麦克风…",
                microphoneActive = false,
                microphoneRequestPending = true,
            ),
        )
        streamer.start(
            output,
            onStarted = {
                if (generation == microphoneGeneration) {
                    sendMicrophoneState(true, commandId)
                    mainHandler.post {
                        if (generation == microphoneGeneration && streamOutput === output) {
                            microphoneActionInFlight = false
                            publish(
                                Snapshot(
                                    state = State.STREAMING,
                                    message = "手机麦克风正在回传",
                                    microphoneActive = true,
                                    microphoneRequestPending = false,
                                    playbackActive = snapshot.playbackActive,
                                    playbackDescription = snapshot.playbackDescription,
                                    pairing = connectedPairing,
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
                        microphoneActionInFlight = false
                        streamer.stop()
                        updateConnectionLocks()
                        sendMicrophoneState(false, commandId)
                        sendControlError("回传失败：$message")
                        publish(
                            Snapshot(
                                state = State.CONNECTED,
                                message = "麦克风回传失败：$message",
                                playbackActive = snapshot.playbackActive,
                                playbackDescription = snapshot.playbackDescription,
                                pairing = connectedPairing,
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

    private fun sendMicrophoneState(enabled: Boolean, commandId: Long = 0L) {
        val output = streamOutput ?: return
        runCatching {
            synchronized(output) {
                val hasCommandId = commandId > 0L
                // 帧长度为 2 或 10；帧体第一个字节始终是类型 2，命令编号放在尾部。
                output.writeInt(if (hasCommandId) 10 else 2)
                output.writeByte(2)
                output.writeByte(if (enabled) 1 else 0)
                if (hasCommandId) {
                    output.writeLong(commandId)
                }
                output.flush()
            }
        }
    }

    private fun sendMicrophoneRequest(enabled: Boolean, requestId: Long): Boolean {
        val output = streamOutput ?: return false
        return runCatching {
            synchronized(output) {
                output.writeInt(10)
                output.writeByte(7)
                output.writeByte(if (enabled) 1 else 0)
                output.writeLong(requestId)
                output.flush()
            }
        }.isSuccess
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

    /** 在主动断开前通知 Windows，避免它把用户操作误判为网络中断并立即广播重连。 */
    private fun sendDisconnectNotice() {
        val output = streamOutput ?: return
        runCatching {
            synchronized(output) {
                output.writeInt(1)
                output.writeByte(13)
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

    private fun handleFeatureFrame(type: Int, payload: ByteArray) {
        if (type != ProtocolV3.TYPE_SCAN_REQUEST && type != ProtocolV3.TYPE_CAPTURE_REQUEST) return
        runCatching {
            val value = JSONObject(payload.toString(Charsets.UTF_8))
            val requestId = value.optString("requestId").takeIf { it.isNotBlank() }
                ?: error("功能请求缺少 requestId")
            val feature = if (type == ProtocolV3.TYPE_SCAN_REQUEST) "scan" else "capture"
            val mode = value.optString("mode", if (feature == "scan") "camera" else "photo")
            updateFeatureRequestNotification(feature, requestId, mode)
        }.onFailure {
            updateNotification("电脑发来的功能请求无效")
        }
    }

    private fun fail(message: String) {
        FeatureSessionRegistry.clear()
        ++connectionGeneration
        ++microphoneGeneration
        ++microphoneRequestGeneration
        pendingMicrophoneEnabled = null
        microphoneActionInFlight = false
        streamer.stop()
        remotePlayer.stop()
        microphoneRequested = false
        playbackRequested = false
        streamOutput = null
        connectedPairing = null
        reconnectTarget = null
        connectionWasEstablished = false
        waitingForReconnect = false
        clearActivePairing()
        reconnectListenerRunning = false
        synchronized(this) { reconnectSocket?.close() }
        client.disconnect()
        releaseConnectionLocks()
        cancelWatchdog()
        publish(Snapshot(State.ERROR, message))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun enterReconnectWait(pairing: PairingInfo, reason: String) {
        ++connectionGeneration
        ++microphoneGeneration
        ++microphoneRequestGeneration
        pendingMicrophoneEnabled = null
        pendingMicrophoneRequestId = 0L
        lastMicrophoneCommandId = 0L
        microphoneActionInFlight = false
        streamer.stop()
        remotePlayer.stop()
        microphoneRequested = false
        playbackRequested = false
        streamOutput = null
        connectedPairing = null
        reconnectTarget = pairing
        persistActivePairing(pairing)
        connectionWasEstablished = false
        waitingForReconnect = true
        client.disconnect()
        releaseConnectionLocks()
        publish(
            Snapshot(
                state = State.CONNECTING,
                message = "$reason；等待电脑发起重连…",
                pairing = pairing,
            ),
        )
        startAsForeground("等待电脑发起重连", microphoneActive = false)
        scheduleWatchdog()
    }

    private fun startReconnectListener() {
        reconnectListenerRunning = true
        reconnectExecutor.execute {
            try {
                DatagramSocket(RECONNECT_PORT).use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 1_000
                    synchronized(this) { reconnectSocket = socket }
                    while (reconnectListenerRunning) {
                        val packet = DatagramPacket(ByteArray(RECONNECT_PACKET_BYTES), RECONNECT_PACKET_BYTES)
                        try {
                            socket.receive(packet)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue
                        }
                        val sourceAddress = packet.address.hostAddress ?: continue
                        val parts = String(
                            packet.data,
                            packet.offset,
                            packet.length,
                            Charsets.US_ASCII,
                        ).trim().split(' ')
                        if (parts.size != 3 || parts[0] != "VSP_RECONNECT") continue
                        val session = parts[1].uppercase()
                        val port = parts[2].toIntOrNull() ?: continue
                        if (session.length != 32
                            || !session.all { it in "0123456789ABCDEF" }
                            || port !in 1..65535
                        ) continue
                        val saved = savedPairingStore.findBySession(session) ?: continue
                        mainHandler.post {
                            val target = reconnectTarget
                            if (target != null
                                && !target.session.equals(session, ignoreCase = true)
                            ) return@post
                            if (snapshot.state == State.CONNECTED
                                || snapshot.state == State.STREAMING
                                || (snapshot.state == State.CONNECTING && !waitingForReconnect)
                            ) return@post
                            startConnection(
                                saved.copy(host = sourceAddress, port = port),
                                allowLocateFallback = false,
                                keepAliveOnFailure = true,
                            )
                        }
                    }
                }
            } catch (_: SocketException) {
                // 服务主动销毁时关闭 DatagramSocket 属于正常生命周期。
            } finally {
                synchronized(this) { reconnectSocket = null }
                reconnectListenerRunning = false
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务列表移除界面不代表用户要求断开连接。前台服务继续保留，
        // 让系统和国产 ROM 不会把它误判为可清理的界面进程。
        if (reconnectTarget != null || streamOutput != null) {
            runCatching {
                startAsForeground(snapshot.message, microphoneActive = microphoneRequested)
            }
            scheduleWatchdog()
        }
        super.onTaskRemoved(rootIntent)
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

    private fun buildNotification(message: String, openIntent: PendingIntent? = null) =
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
            .apply {
                if (openIntent != null) addAction(0, "打开", openIntent)
            }
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateFeatureRequestNotification(feature: String, requestId: String, mode: String) {
        val action = if (feature == "scan") "scan" else "capture"
        val intent = Intent(this, TouchpadActivity::class.java)
            .setAction(TouchpadActivity.ACTION_FEATURE_REQUEST)
            .putExtra(TouchpadActivity.EXTRA_FEATURE, action)
            .putExtra(TouchpadActivity.EXTRA_REQUEST_ID, requestId)
            .putExtra(TouchpadActivity.EXTRA_FEATURE_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestId.hashCode() and Int.MAX_VALUE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (feature == "scan") "电脑请求手机扫码" else "电脑请求手机拍照"
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification("$title · 点击打开确认", pendingIntent),
        )
    }

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

    private fun persistActivePairing(pairing: PairingInfo) {
        servicePreferences.edit {
            putBoolean(KEY_ACTIVE, true)
            putString(KEY_HOST, pairing.host)
            putInt(KEY_PORT, pairing.port)
            putString(KEY_SESSION, pairing.session)
            putString(KEY_SECRET, pairing.secret)
        }
    }

    private fun loadActivePairing(): PairingInfo? {
        if (!servicePreferences.getBoolean(KEY_ACTIVE, false)) return null
        val pairing = PairingInfo(
            host = servicePreferences.getString(KEY_HOST, null).orEmpty(),
            port = servicePreferences.getInt(KEY_PORT, 0),
            session = servicePreferences.getString(KEY_SESSION, null).orEmpty(),
            secret = servicePreferences.getString(KEY_SECRET, null).orEmpty(),
        )
        return pairing.takeIf {
            it.host.isNotBlank()
                && it.port in 1..65535
                && it.session.length == 32
                && it.secret.length == 32
        }
    }

    private fun clearActivePairing() {
        servicePreferences.edit { clear() }
    }

    private fun scheduleWatchdog(delayMilliseconds: Long = WATCHDOG_INTERVAL_MILLISECONDS) {
        scheduleWatchdog(this, delayMilliseconds)
    }

    private fun cancelWatchdog() {
        cancelWatchdog(this)
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
        // 非用户主动断开时保留下一次闹钟，给系统回收后的服务重建留出入口。
        if (servicePreferences.getBoolean(KEY_ACTIVE, false)) {
            scheduleWatchdog()
        }
        FeatureSessionRegistry.clear()
        ++connectionGeneration
        ++microphoneGeneration
        ++microphoneRequestGeneration
        pendingMicrophoneEnabled = null
        microphoneActionInFlight = false
        streamer.stop()
        remotePlayer.stop()
        microphoneRequested = false
        playbackRequested = false
        streamOutput = null
        connectedPairing = null
        reconnectTarget = null
        connectionWasEstablished = false
        waitingForReconnect = false
        reconnectListenerRunning = false
        synchronized(this) {
            reconnectSocket?.close()
            reconnectSocket = null
        }
        client.close()
        discovery.close()
        releaseConnectionLocks()
        reconnectExecutor.shutdownNow()
        super.onDestroy()
    }
}
