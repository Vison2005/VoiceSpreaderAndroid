package com.voicespreader.remote

import android.os.Build
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 独立的 protocol 3 控制连接。旧 PairingClient 仍负责 protocol 2 音频兼容。 */
class ProtocolV3Client {
    interface Session {
        val remoteCapabilities: Set<String>

        fun sendPointer(value: ProtocolV3.PointerPayload)

        fun sendButton(value: ProtocolV3.ButtonPayload)

        fun sendScroll(value: ProtocolV3.ScrollPayload)

        fun sendZoom(value: ProtocolV3.ZoomPayload)

        fun sendShortcut(value: JSONObject)

        fun sendJson(type: Int, value: JSONObject)

        fun close()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    @Volatile
    private var socket: Socket? = null

    fun connect(
        info: PairingInfo,
        deviceId: String,
        capabilities: Set<String>,
        onConnected: (Session) -> Unit,
        onFrame: (Int, ByteArray) -> Unit = { _, _ -> },
        onError: (String) -> Unit,
    ) {
        val currentGeneration = generation.incrementAndGet()
        closeSocket()
        executor.execute {
            runCatching {
                val connection = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(info.host, info.port), 4_000)
                }
                if (currentGeneration != generation.get()) {
                    connection.close()
                    return@runCatching
                }
                socket = connection
                val output = ProtocolV3.outputFor(connection.getOutputStream())
                val input = BufferedInputStream(connection.getInputStream(), 32 * 1024)
                output.write(ProtocolV3.buildHello(
                    info.session,
                    info.secret,
                    deviceId,
                    "${Build.MANUFACTURER} ${Build.MODEL}",
                    capabilities,
                ).toByteArray(Charsets.UTF_8))
                output.flush()
                val response = JSONObject(readAsciiLine(input, 16 * 1024))
                if (response.optString("type") != "accepted") {
                    error(response.optString("message", "电脑拒绝了 protocol 3 连接"))
                }
                if (response.optInt("protocol", 0) < ProtocolV3.VERSION) {
                    error("电脑端不支持 protocol 3")
                }
                val remoteCapabilities = buildSet {
                    response.optJSONArray("capabilities")?.let { values ->
                        for (index in 0 until values.length()) {
                            values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                val session = ControlSession(connection, output, remoteCapabilities)
                onConnected(session)
                readFrames(input, currentGeneration, onFrame)
            }.onFailure { error ->
                if (currentGeneration == generation.get()) {
                    closeSocket()
                    onError(error.message ?: "无法建立 protocol 3 连接")
                }
            }
        }
    }

    fun disconnect() {
        generation.incrementAndGet()
        closeSocket()
    }

    fun close() {
        disconnect()
        executor.shutdownNow()
    }

    private fun readFrames(input: InputStream, currentGeneration: Int, onFrame: (Int, ByteArray) -> Unit) {
        val framed = DataInputStream(input)
        while (currentGeneration == generation.get()) {
            val bodyLength = framed.readInt()
            if (bodyLength !in 1..ProtocolV3.MAX_CONTROL_FRAME_BYTES) {
                error("protocol 3 控制帧长度无效")
            }
            val type = framed.readUnsignedByte()
            val payload = ByteArray(bodyLength - 1)
            framed.readFully(payload)
            // 只通过上层回调分发一次。输入通道不应因某个可选功能回调抛异常而断开。
            runCatching { FileTransferCoordinator.handleFrame(type, payload) }
            runCatching { onFrame(type, payload) }
        }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    private class ControlSession(
        private val connection: Socket,
        private val output: DataOutputStream,
        override val remoteCapabilities: Set<String>,
    ) : Session {
        private data class QueuedFrame(
            val type: Int,
            val payload: ByteArray,
            val fileChannel: Boolean = false,
            val pointer: ProtocolV3.PointerPayload? = null,
        )

        private val queueLock = java.lang.Object()
        private val queue = ArrayDeque<QueuedFrame>()
        private val writer = Executors.newSingleThreadExecutor()
        private var closed = false

        init {
            // 网络写入不能占用 Android 触摸事件线程，否则一次 TCP 阻塞就会把 MOVE 事件排成“历史轨迹”。
            writer.execute(::writeLoop)
        }

        override fun sendPointer(value: ProtocolV3.PointerPayload) {
            enqueue(
                QueuedFrame(
                    ProtocolV3.TYPE_INPUT_POINTER,
                    ProtocolV3.encodePointer(value),
                    pointer = value,
                ),
                coalesceMove = value.action == ProtocolV3.PointerAction.MOVE,
            )
        }

        override fun sendButton(value: ProtocolV3.ButtonPayload) {
            enqueue(
                QueuedFrame(
                    ProtocolV3.TYPE_INPUT_BUTTON,
                    ProtocolV3.encodeButton(value),
                ),
            )
        }

        override fun sendScroll(value: ProtocolV3.ScrollPayload) {
            enqueue(
                QueuedFrame(
                    ProtocolV3.TYPE_INPUT_SCROLL,
                    ProtocolV3.encodeScroll(value),
                ),
            )
        }

        override fun sendZoom(value: ProtocolV3.ZoomPayload) {
            enqueue(
                QueuedFrame(
                    ProtocolV3.TYPE_INPUT_ZOOM,
                    ProtocolV3.encodeZoom(value),
                ),
            )
        }

        override fun sendShortcut(value: JSONObject) {
            enqueue(
                QueuedFrame(
                    ProtocolV3.TYPE_SHORTCUT_EXECUTE,
                    ProtocolV3.jsonFrame(value),
                ),
            )
        }

        override fun sendJson(type: Int, value: JSONObject) {
            enqueue(QueuedFrame(type, ProtocolV3.jsonFrame(value)))
        }

        override fun close() {
            synchronized(queueLock) {
                closed = true
                queue.clear()
                queueLock.notifyAll()
            }
            writer.shutdownNow()
            runCatching { connection.close() }
        }

        private fun enqueue(frame: QueuedFrame, coalesceMove: Boolean = false) {
            synchronized(queueLock) {
                if (closed) throw IOException("protocol 3 控制连接已关闭")

                // 连续 MOVE 只合并尚未写出的尾帧，累计增量并使用最新序号，保持路径和速度，避免旧轨迹堆积。
                if (coalesceMove) {
                    val last = queue.lastOrNull()
                    val previous = last?.pointer
                    val current = frame.pointer
                    if (previous?.action == ProtocolV3.PointerAction.MOVE
                        && current != null
                        && previous.pointerId == current.pointerId
                    ) {
                        val merged = current.copy(
                            deltaX = previous.deltaX + current.deltaX,
                            deltaY = previous.deltaY + current.deltaY,
                        )
                        queue.removeLast()
                        queue.addLast(
                            frame.copy(
                                payload = ProtocolV3.encodePointer(merged),
                                pointer = merged,
                            ),
                        )
                        queueLock.notifyAll()
                        return
                    }
                }

                if (queue.size >= MAX_PENDING_FRAMES) {
                    // 只丢弃最旧的 MOVE；按键、滚轮和 DOWN/UP 事件必须保留。
                    val retained = ArrayDeque<QueuedFrame>(queue.size)
                    var droppedMove = false
                    while (queue.isNotEmpty()) {
                        val candidate = queue.removeFirst()
                        if (!droppedMove && candidate.pointer?.action == ProtocolV3.PointerAction.MOVE) {
                            droppedMove = true
                        } else {
                            retained.addLast(candidate)
                        }
                    }
                    queue.addAll(retained)
                    if (!droppedMove && coalesceMove) return
                    if (!droppedMove && queue.isNotEmpty()) queue.removeFirst()
                }
                queue.addLast(frame)
                queueLock.notifyAll()
            }
        }

        private fun writeLoop() {
            try {
                while (true) {
                    val batch = takeBatch() ?: return
                    synchronized(output) {
                        // 一次 flush 写出一小批连续帧，减少每个 MOVE 独占一个 TCP 包的额外等待。
                        batch.forEach { frame ->
                            ProtocolV3.writeFrame(
                                output,
                                frame.type,
                                frame.payload,
                                frame.fileChannel,
                                flush = false,
                            )
                        }
                        output.flush()
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // 关闭 socket 让读取线程统一报告一次断联；队列中的旧 MOVE 不得在重连后回放。
                synchronized(queueLock) {
                    closed = true
                    queue.clear()
                    queueLock.notifyAll()
                }
                runCatching { connection.close() }
            }
        }

        private fun takeBatch(): List<QueuedFrame>? {
            synchronized(queueLock) {
                while (queue.isEmpty() && !closed) {
                    queueLock.wait()
                }
                if (closed) return null

                val batch = ArrayList<QueuedFrame>(MAX_BATCH_FRAMES)
                val deadline = System.nanoTime() + BATCH_WAIT_NANOSECONDS
                while (batch.size < MAX_BATCH_FRAMES) {
                    while (queue.isEmpty() && !closed) {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0L) break
                        val millis = remaining / 1_000_000L
                        val nanos = (remaining % 1_000_000L).toInt()
                        queueLock.wait(millis, nanos)
                    }
                    if (closed) return null
                    if (queue.isEmpty()) break
                    batch.add(queue.removeFirst())
                }
                return batch
            }
        }

        private companion object {
            const val MAX_PENDING_FRAMES = 128
            const val MAX_BATCH_FRAMES = 16
            const val BATCH_WAIT_NANOSECONDS = 1_500_000L
        }
    }

    private fun readAsciiLine(input: InputStream, maximumBytes: Int): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < maximumBytes) {
            val value = input.read()
            if (value < 0) error("电脑已断开 protocol 3 连接")
            if (value == '\n'.code) break
            bytes += value.toByte()
        }
        if (bytes.size >= maximumBytes) error("protocol 3 握手响应过长")
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}
