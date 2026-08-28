package com.voicespreader.remote

import android.os.Build
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

data class RemoteAudioFrame(
    val firstFrameIndex: Long,
    val sampleRate: Int,
    val channels: Int,
    val pcm16LittleEndian: ByteArray,
)

class PairingClient {
    private val executor = Executors.newSingleThreadExecutor()
    private val connectionLock = Any()

    @Volatile
    private var socket: Socket? = null
    private var connectionGeneration = 0

    fun connect(
        info: PairingInfo,
        deviceId: String,
        onConnected: (DataOutputStream, Boolean) -> Unit,
        onMicrophoneCommand: (Boolean) -> Unit,
        onPlaybackCommand: (Boolean) -> Unit,
        onPlaybackFrame: (RemoteAudioFrame) -> Unit,
        onError: (String) -> Unit,
    ) {
        val generation = synchronized(connectionLock) {
            ++connectionGeneration
            runCatching { socket?.close() }
            socket = null
            connectionGeneration
        }
        executor.execute {
            runCatching {
                val connection = Socket()
                connection.tcpNoDelay = true
                connection.connect(InetSocketAddress(info.host, info.port), 4000)
                synchronized(connectionLock) {
                    if (generation != connectionGeneration) {
                        connection.close()
                        error("连接已取消")
                    }
                    socket = connection
                }
                val output = DataOutputStream(BufferedOutputStream(connection.getOutputStream(), 32768))
                val input = BufferedInputStream(connection.getInputStream(), 32768)
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("protocol", 2)
                    .put("session", info.session)
                    .put("secret", info.secret)
                    .put("deviceId", deviceId)
                    .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .toString() + "\n"
                output.write(hello.toByteArray(Charsets.UTF_8))
                output.flush()

                val responseLine = readAsciiLine(input, 4096)
                val response = JSONObject(responseLine)
                if (response.optString("type") != "accepted") {
                    error(response.optString("message", "电脑拒绝了配对"))
                }
                if (response.optInt("protocol", 0) < 2) {
                    error("电脑端版本过旧，不支持当前设备互联协议")
                }
                onConnected(output, response.optBoolean("microphoneRequests", false))

                val framedInput = DataInputStream(input)
                while (generation == synchronized(connectionLock) { connectionGeneration }) {
                    val bodyLength = framedInput.readInt()
                    if (bodyLength !in 1..MAXIMUM_FRAME_BYTES) {
                        error("电脑发送的数据帧长度无效")
                    }
                    val type = framedInput.readUnsignedByte()
                    var remaining = bodyLength - 1
                    when (type) {
                        10 -> {
                            if (remaining < 1) error("麦克风控制帧无效")
                            onMicrophoneCommand(framedInput.readUnsignedByte() != 0)
                            remaining--
                        }

                        11 -> {
                            if (remaining < 1) error("播放控制帧无效")
                            onPlaybackCommand(framedInput.readUnsignedByte() != 0)
                            remaining--
                        }

                        12 -> {
                            if (remaining < 13) error("Windows 音频帧无效")
                            val firstFrameIndex = framedInput.readLong()
                            val sampleRate = framedInput.readInt()
                            val channels = framedInput.readUnsignedByte()
                            remaining -= 13
                            val maximumPcmBytes =
                                sampleRate.toLong() * channels * Short.SIZE_BYTES *
                                    MAXIMUM_AUDIO_FRAME_MILLISECONDS / 1000
                            if (sampleRate !in 8_000..192_000
                                || channels !in 1..2
                                || remaining <= 0
                                || remaining > maximumPcmBytes
                                || remaining % 2 != 0
                            ) {
                                error("Windows 音频格式无效")
                            }
                            val pcm = ByteArray(remaining)
                            framedInput.readFully(pcm)
                            remaining = 0
                            onPlaybackFrame(
                                RemoteAudioFrame(firstFrameIndex, sampleRate, channels, pcm),
                            )
                        }
                    }
                    if (remaining > 0) {
                        framedInput.skipFully(remaining)
                    }
                }
            }.onFailure { error ->
                val shouldReport = synchronized(connectionLock) {
                    val current = generation == connectionGeneration
                    if (current) {
                        runCatching { socket?.close() }
                        socket = null
                    }
                    current
                }
                if (shouldReport) {
                    onError(error.message ?: "无法连接电脑")
                }
            }
        }
    }

    fun disconnect() {
        synchronized(connectionLock) {
            ++connectionGeneration
            runCatching { socket?.close() }
            socket = null
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    fun close() {
        disconnect()
        executor.shutdownNow()
    }

    private fun readAsciiLine(input: InputStream, maximumBytes: Int): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < maximumBytes) {
            val value = input.read()
            if (value < 0) error("电脑已断开连接")
            if (value == '\n'.code) break
            bytes += value.toByte()
        }
        if (bytes.size >= maximumBytes) error("电脑握手响应过长")
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun DataInputStream.skipFully(byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) error("电脑在数据帧传输期间断开连接")
            remaining -= skipped
        }
    }

    private companion object {
        const val MAXIMUM_FRAME_BYTES = 256 * 1024
        const val MAXIMUM_AUDIO_FRAME_MILLISECONDS = 100
    }
}
