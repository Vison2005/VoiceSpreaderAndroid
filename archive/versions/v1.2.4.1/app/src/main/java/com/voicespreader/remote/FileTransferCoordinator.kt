package com.voicespreader.remote

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 接收 Windows 的 FILE_ACCEPT 后启动对应的独立文件流。 */
object FileTransferCoordinator {
    private data class Pending(
        val client: FileTransferClient,
        val sources: List<FileTransferSource>,
        val offsets: MutableMap<UUID, Long> = mutableMapOf(),
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    fun register(transferId: String, client: FileTransferClient, sources: List<FileTransferSource>) {
        pending[transferId] = Pending(client, sources)
    }

    fun handleFrame(type: Int, payload: ByteArray) {
        if (type != ProtocolV3.TYPE_FILE_ACCEPT) return
        runCatching {
            val value = JSONObject(payload.toString(Charsets.UTF_8))
            val transferId = value.getString("transferId")
            val task = pending[transferId] ?: return
            val items = value.optJSONArray("items")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val itemId = UUID.fromString(item.getString("itemId"))
                    task.offsets[itemId] = item.optLong("acceptedOffset", 0L)
                }
            } else {
                // 兼容早期开发包的单 item 形式，正式协议使用 items 数组。
                val itemId = UUID.fromString(value.getString("itemId"))
                task.offsets[itemId] = value.optLong("acceptedOffset", 0L)
            }
            if (task.offsets.keys.containsAll(task.sources.map { it.item.itemId })) {
                pending.remove(transferId)
                task.client.sendAccepted(
                    transferId,
                    task.sources,
                    task.offsets.toMap(),
                )
            }
        }
    }

    fun cancel(transferId: String) {
        pending.remove(transferId)?.client?.cancel(transferId)
    }

    fun clear() {
        pending.values.forEach { it.client.close() }
        pending.clear()
    }
}
