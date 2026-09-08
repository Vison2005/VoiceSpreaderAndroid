package com.voicespreader.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** 供 Activity 与前台连接服务共享当前 protocol 3 功能会话。 */
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

    fun ensureConnected(context: Context, onError: (String) -> Unit = {}) {
        val endpoint = pairing ?: return onError("尚未建立音频连接")
        val id = deviceId ?: return onError("设备标识尚未准备好")
        if (control != null || client != null) return
        val nextClient = ProtocolV3Client()
        client = nextClient
        nextClient.connect(
            endpoint,
            id,
            capabilities(context),
            onConnected = { session -> control = session },
            onFrame = { type, payload ->
                handleFrame(type, payload)
            },
            onError = {
                if (client === nextClient) {
                    control = null
                    client = null
                }
                onError(it)
            },
        )
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
                            if (id.isNotBlank() && name.isNotBlank()) add(RemoteAppShortcut(id, name))
                        }
                    }
                }
                appCatalog = parsed
                onAppCatalogChanged?.invoke(parsed)
            }
        }
        onFrame?.invoke(type, payload)
    }

    /** 清理已经断开的控制会话，但保留音频服务保存的配对信息，允许下次自动重连。 */
    fun invalidate(session: ProtocolV3Client.Session? = null) {
        if (session != null && control !== session) return
        control = null
        client?.disconnect()
        client = null
    }

    private fun capabilities(context: Context): Set<String> = buildSet {
        add(ProtocolV3.CAP_TOUCHPAD)
        add(ProtocolV3.CAP_SHORTCUT)
        add(ProtocolV3.CAP_APP_LAUNCH)
        add(ProtocolV3.CAP_FILE_SEND)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            add(ProtocolV3.CAP_CAMERA_CAPTURE)
            add(ProtocolV3.CAP_SCAN_CAMERA)
        }
        // Photo Picker 不需要全盘相册权限，Android 端始终可以在用户选择后读取 URI。
        add(ProtocolV3.CAP_SCAN_GALLERY)
    }

    fun clear() {
        client?.close()
        client = null
        control?.close()
        control = null
        FileTransferCoordinator.clear()
        pairing = null
        deviceId = null
        onFrame = null
        onAppCatalogChanged = null
        appCatalog = emptyList()
    }
}
