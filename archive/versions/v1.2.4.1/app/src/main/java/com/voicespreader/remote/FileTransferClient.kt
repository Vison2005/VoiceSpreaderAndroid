package com.voicespreader.remote

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class FileTransferSource(
    val item: ProtocolV3.FileItem,
    val openStream: () -> InputStream,
)

data class FileTransferProgress(
    val transferId: String,
    val itemId: UUID,
    val sentBytes: Long,
    val totalBytes: Long,
    val finished: Boolean = false,
    val error: String? = null,
)

/** 独立 file channel 的流式发送器，不把完整文件读入内存。 */
class FileTransferClient(
    private val pairing: PairingInfo,
    private val deviceId: String,
    private val control: ProtocolV3Client.Session,
) {
    private val executor = Executors.newCachedThreadPool()
    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()
    @Volatile
    private var fileSocket: Socket? = null

    fun offer(sources: Collection<FileTransferSource>): String {
        require(sources.isNotEmpty()) { "至少需要一个文件" }
        val transferId = ProtocolV3.newTransferId()
        cancelled[transferId] = AtomicBoolean(false)
        // 先登记待处理项，再发送 OFFER，避免电脑响应过快时 FILE_ACCEPT 先于登记到达。
        FileTransferCoordinator.register(transferId, this, sources.toList())
        val items = sources.map { it.item }
        control.sendJson(
            ProtocolV3.TYPE_FILE_OFFER,
            ProtocolV3.offerJson(transferId, items),
        )
        return transferId
    }

    /**
     * Windows 已经通过 FILE_ACCEPT 确认后调用。acceptedOffsets 的 key 必须来自 FILE_OFFER。
     * 首版严格按 items 顺序发送，同一个 item 不建立并行流。
     */
    fun sendAccepted(
        transferId: String,
        sources: List<FileTransferSource>,
        acceptedOffsets: Map<UUID, Long> = emptyMap(),
        onProgress: (FileTransferProgress) -> Unit = {},
        onFinished: (String, Boolean, String?) -> Unit = { _, _, _ -> },
    ) {
        require(sources.isNotEmpty()) { "至少需要一个文件" }
        val cancellation = cancelled.getOrPut(transferId) { AtomicBoolean(false) }
        executor.execute {
            runCatching {
                val socket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    sendBufferSize = 1024 * 1024
                    receiveBufferSize = 64 * 1024
                    connect(InetSocketAddress(pairing.host, pairing.port), 4_000)
                }
                fileSocket = socket
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
                val input = BufferedInputStream(socket.getInputStream(), 8 * 1024)
                val hello = ProtocolV3.buildHello(
                    pairing.session,
                    pairing.secret,
                    deviceId,
                    "Android 文件通道",
                    setOf(ProtocolV3.CAP_FILE_SEND),
                    channel = ProtocolV3.CHANNEL_FILE,
                    transferId = transferId,
                )
                output.write(hello.toByteArray(Charsets.UTF_8))
                output.flush()
                val response = JSONObject(readAsciiLine(input, 16 * 1024))
                if (response.optString("type") != "accepted") {
                    error(response.optString("message", "电脑拒绝文件通道"))
                }
                if (response.optInt("protocol", 0) < ProtocolV3.VERSION) {
                    error("电脑端不支持 protocol 3 文件通道")
                }
                sources.forEach { source ->
                    if (cancellation.get()) error("传输已取消")
                    val acceptedOffset = acceptedOffsets[source.item.itemId] ?: 0L
                    sendItem(
                        output = output,
                        transferId = transferId,
                        source = source,
                        acceptedOffset = acceptedOffset,
                        cancellation = cancellation,
                        onProgress = onProgress,
                    )
                }
                onFinished(transferId, true, null)
            }.onFailure { failure ->
                onFinished(transferId, false, failure.message ?: "文件传输失败")
            }
            runCatching { fileSocket?.close() }
            fileSocket = null
            cancelled.remove(transferId)
        }
    }

    fun cancel(transferId: String) {
        cancelled.getOrPut(transferId) { AtomicBoolean(false) }.set(true)
        runCatching {
            control.sendJson(
                ProtocolV3.TYPE_FILE_CANCEL,
                JSONObject().put("transferId", transferId),
            )
        }
        runCatching { fileSocket?.close() }
    }

    fun close() {
        cancelled.values.forEach { it.set(true) }
        runCatching { fileSocket?.close() }
        executor.shutdownNow()
    }

    private fun sendItem(
        output: DataOutputStream,
        transferId: String,
        source: FileTransferSource,
        acceptedOffset: Long,
        cancellation: AtomicBoolean,
        onProgress: (FileTransferProgress) -> Unit,
    ) {
        require(acceptedOffset in 0..source.item.size) { "文件续传偏移无效" }
        val digest = MessageDigest.getInstance("SHA-256")
        source.openStream().use { input ->
            hashPrefixAndSkip(input, acceptedOffset, digest)
            var offset = acceptedOffset
            val buffer = ByteArray(ProtocolV3.FILE_CHUNK_BYTES)
            while (offset < source.item.size) {
                if (cancellation.get()) error("传输已取消")
                val remaining = source.item.size - offset
                val requested = minOf(buffer.size.toLong(), remaining).toInt()
                val count = readAtMost(input, buffer, requested)
                if (count <= 0) error("读取文件时提前结束")
                digest.update(buffer, 0, count)
                val payload = ByteArray(16 + Long.SIZE_BYTES + count)
                val idBytes = ProtocolV3.uuidBytes(source.item.itemId)
                System.arraycopy(idBytes, 0, payload, 0, idBytes.size)
                writeLongBigEndian(payload, 16, offset)
                System.arraycopy(buffer, 0, payload, 24, count)
                ProtocolV3.writeFrame(output, ProtocolV3.TYPE_FILE_CHUNK, payload, fileChannel = true)
                offset += count
                onProgress(
                    FileTransferProgress(
                        transferId,
                        source.item.itemId,
                        offset,
                        source.item.size,
                    ),
                )
            }
            val complete = JSONObject()
                .put("transferId", transferId)
                .put("itemId", source.item.itemId.toString())
                .put("sha256", digest.digest().joinToString("") { byte -> "%02x".format(byte) })
                .put("size", source.item.size)
            control.sendJson(ProtocolV3.TYPE_FILE_COMPLETE, complete)
            onProgress(
                FileTransferProgress(
                    transferId,
                    source.item.itemId,
                    source.item.size,
                    source.item.size,
                    finished = true,
                ),
            )
        }
    }

    private fun readAtMost(input: InputStream, buffer: ByteArray, expected: Int): Int {
        var total = 0
        while (total < expected) {
            val count = input.read(buffer, total, expected - total)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (input.available() <= 0) break
        }
        return total
    }

    private fun hashPrefixAndSkip(input: InputStream, count: Long, digest: MessageDigest) {
        var remaining = count
        val prefixBuffer = ByteArray(32 * 1024)
        while (remaining > 0) {
            val expected = minOf(prefixBuffer.size.toLong(), remaining).toInt()
            val countRead = input.read(prefixBuffer, 0, expected)
            if (countRead <= 0) error("无法定位文件续传偏移")
            digest.update(prefixBuffer, 0, countRead)
            remaining -= countRead
        }
    }

    private fun writeLongBigEndian(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until Long.SIZE_BYTES) {
            target[offset + index] = (value ushr (56 - index * 8)).toByte()
        }
    }

    private fun readAsciiLine(input: InputStream, maximumBytes: Int): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < maximumBytes) {
            val value = input.read()
            if (value < 0) error("电脑已断开文件通道")
            if (value == '\n'.code) break
            bytes += value.toByte()
        }
        if (bytes.size >= maximumBytes) error("文件通道握手响应过长")
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}
