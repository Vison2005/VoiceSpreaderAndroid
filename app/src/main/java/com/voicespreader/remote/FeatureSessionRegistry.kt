package com.voicespreader.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.math.min

/** 管理触摸板等功能通道，避免页面生命周期影响音频连接。 */
object FeatureSessionRegistry {
    data class RemoteAppShortcut(val id: String, val name: String)

    @Volatile
    var control: ProtocolV3Client.Session? = null

    @Volatile
    var pairing: PairingInfo? = null

    @Volatile
    var deviceId: String? = null

    @Volatile
    var onFrame: ((Int, ByteArray) -> Unit)? = null

    @Volatile
    var appCatalog: List<RemoteAppShortcut> = emptyList()

    @Volatile
    var onAppCatalogChanged: ((List<RemoteAppShortcut>) -> Unit)? = null

    @Volatile
    private var client: ProtocolV3Client? = null

    private val lifecycleLock = Any()
    private val retryHandler = Handler(Looper.getMainLooper())
    private val pendingReadyCallbacks = ArrayDeque<(ProtocolV3Client.Session) -> Unit>()
    private var applicationContext: Context? = null
    private var wantsConnection = false
    private var retryScheduled = false
    private var retryDelayMilliseconds = 500L

    fun ensureConnected(
        context: Context,
        onConnected: ((ProtocolV3Client.Session) -> Unit)? = null,
        onError: (String) -> Unit = {},
    ) {
        val endpoint = pairing
        if (endpoint == null) {
            onError("尚未建立音频连接")
            return
        }
        val id = deviceId
        if (id.isNullOrBlank()) {
            onError("设备标识尚未准备好")
            return
        }

        var immediateSession: ProtocolV3Client.Session? = null
        var nextClient: ProtocolV3Client? = null
        synchronized(lifecycleLock) {
            applicationContext = context.applicationContext
            wantsConnection = true
            control?.let { immediateSession = it }
                ?: run {
                    // 连接建立期间触摸 MOVE 可能每秒产生数百次；回调只需少量代表即可，
                    // 不能让每个事件都排队，连接成功后再瞬间执行成百上千个回调。
                    if (onConnected != null && pendingReadyCallbacks.size < MAX_READY_CALLBACKS) {
                        pendingReadyCallbacks.addLast(onConnected)
                    }
                    // 已经安排退避重连时只登记回调，不再为每个触摸事件创建新的 socket。
                    // 否则 ACTION_MOVE 高频到来会把一个失败连接放大成并行重连风暴。
                    if (client == null && !retryScheduled) {
                        nextClient = ProtocolV3Client()
                        client = nextClient
                    }
                }
        }

        immediateSession?.let {
            onConnected?.let { callback -> runCatching { callback(it) } }
            return
        }

        val connectingClient = nextClient ?: return
        connectingClient.connect(
            endpoint,
            id,
            capabilities(context),
            onConnected = { session ->
                val callbacks = synchronized(lifecycleLock) {
                    if (!wantsConnection || client !== connectingClient) {
                        null
                    } else {
                        retryDelayMilliseconds = 500L
                        control = session
                        pendingReadyCallbacks.toList().also { pendingReadyCallbacks.clear() }
                    }
                }
                if (callbacks == null) {
                    session.close()
                } else {
                    callbacks.forEach { callback -> runCatching { callback(session) } }
                }
            },
            onFrame = ::handleFrame,
            onError = { message ->
                var shouldRetry = false
                synchronized(lifecycleLock) {
                    if (client === connectingClient) {
                        client = null
                        control = null
                        shouldRetry = wantsConnection
                    }
                }
                if (shouldRetry) {
                    scheduleReconnect()
                }
                onError(message)
            },
        )
    }

    private fun scheduleReconnect() {
        val scheduled = synchronized(lifecycleLock) {
            if (!wantsConnection || retryScheduled || client != null) {
                null
            } else {
                retryScheduled = true
                val delay = retryDelayMilliseconds
                retryDelayMilliseconds = min(5_000L, retryDelayMilliseconds * 2)
                applicationContext?.let { it to delay }
            }
        } ?: return

        retryHandler.postDelayed({
            synchronized(lifecycleLock) { retryScheduled = false }
            ensureConnected(scheduled.first)
        }, scheduled.second)
    }

    private fun handleFrame(type: Int, payload: ByteArray) {
        if (type == ProtocolV3.TYPE_SHORTCUT_CATALOG) {
            runCatching {
                val root = JSONObject(payload.toString(Charsets.UTF_8))
                val values = root.optJSONArray("apps")
                val parsed = buildList {
                    if (values != null) {
                        for (index in 0 until values.length()) {
                            val app = values.optJSONObject(index) ?: continue
                            val id = app.optString("id").trim()
                            val name = app.optString("name").trim()
                            if (id.isNotBlank() && name.isNotBlank()) {
                                add(RemoteAppShortcut(id, name))
                            }
                        }
                    }
                }
                appCatalog = parsed
                runCatching { onAppCatalogChanged?.invoke(parsed) }
            }
        }
        runCatching { onFrame?.invoke(type, payload) }
    }

    /** 只清理功能通道；配对信息由音频服务保存。 */
    fun invalidate(session: ProtocolV3Client.Session? = null) {
        var shouldRetry = false
        val currentClient = synchronized(lifecycleLock) {
            if (session != null && control !== session) return
            control = null
            client.also {
                client = null
                shouldRetry = wantsConnection
            }
        }
        currentClient?.close()
        if (shouldRetry) scheduleReconnect()
    }

    private fun capabilities(@Suppress("UNUSED_PARAMETER") context: Context): Set<String> =
        setOf(
            ProtocolV3.CAP_TOUCHPAD,
            ProtocolV3.CAP_SHORTCUT,
            ProtocolV3.CAP_APP_LAUNCH,
        )

    fun clear() {
        val currentClient = synchronized(lifecycleLock) {
            wantsConnection = false
            retryScheduled = false
            retryDelayMilliseconds = 500L
            pendingReadyCallbacks.clear()
            val value = client
            client = null
            control = null
            value
        }
        retryHandler.removeCallbacksAndMessages(null)
        currentClient?.close()
        FileTransferCoordinator.clear()
        pairing = null
        deviceId = null
        applicationContext = null
        onFrame = null
        onAppCatalogChanged = null
        appCatalog = emptyList()
    }

    private const val MAX_READY_CALLBACKS = 8
}
