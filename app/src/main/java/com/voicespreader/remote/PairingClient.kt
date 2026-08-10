package com.voicespreader.remote

import android.os.Build
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class PairingClient {
    private val executor = Executors.newSingleThreadExecutor()
    private val connectionLock = Any()

    @Volatile
    private var socket: Socket? = null
    private var connectionGeneration = 0

    fun connect(
        info: PairingInfo,
        onConnected: (DataOutputStream) -> Unit,
        onMicrophoneCommand: (Boolean) -> Unit,
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
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("protocol", 1)
                    .put("session", info.session)
                    .put("secret", info.secret)
                    .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .toString() + "\n"
                output.write(hello.toByteArray(Charsets.UTF_8))
                output.flush()

                val responseLine = readAsciiLine(connection.getInputStream(), 4096)
                val response = JSONObject(responseLine)
                if (response.optString("type") != "accepted") {
                    error(response.optString("message", "电脑拒绝了配对"))
                }
                onConnected(output)

                val input = connection.getInputStream()
                while (generation == synchronized(connectionLock) { connectionGeneration }) {
                    val command = JSONObject(readAsciiLine(input, 4096))
                    if (command.optString("type") == "setMicrophone") {
                        onMicrophoneCommand(command.optBoolean("enabled", false))
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
}
