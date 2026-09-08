package com.voicespreader.remote

import android.os.Build
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
            FileTransferCoordinator.handleFrame(type, payload)
            FeatureSessionRegistry.onFrame?.invoke(type, payload)
            onFrame(type, payload)
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
        override fun sendPointer(value: ProtocolV3.PointerPayload) {
            ProtocolV3.writeFrame(output, ProtocolV3.TYPE_INPUT_POINTER, ProtocolV3.encodePointer(value))
        }

        override fun sendButton(value: ProtocolV3.ButtonPayload) {
            ProtocolV3.writeFrame(output, ProtocolV3.TYPE_INPUT_BUTTON, ProtocolV3.encodeButton(value))
        }

        override fun sendScroll(value: ProtocolV3.ScrollPayload) {
            ProtocolV3.writeFrame(output, ProtocolV3.TYPE_INPUT_SCROLL, ProtocolV3.encodeScroll(value))
        }

        override fun sendShortcut(value: JSONObject) {
            ProtocolV3.writeFrame(
                output,
                ProtocolV3.TYPE_SHORTCUT_EXECUTE,
                ProtocolV3.jsonFrame(value),
            )
        }

        override fun sendJson(type: Int, value: JSONObject) {
            ProtocolV3.writeFrame(output, type, ProtocolV3.jsonFrame(value))
        }

        override fun close() {
            runCatching { connection.close() }
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
