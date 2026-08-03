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

    @Volatile
    private var socket: Socket? = null

    fun connect(
        info: PairingInfo,
        onConnected: (DataOutputStream) -> Unit,
        onError: (String) -> Unit,
    ) {
        disconnect()
        executor.execute {
            runCatching {
                val connection = Socket()
                connection.tcpNoDelay = true
                connection.connect(InetSocketAddress(info.host, info.port), 4000)
                socket = connection
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
                output
            }.onSuccess(onConnected).onFailure {
                disconnect()
                onError(it.message ?: "无法连接电脑")
            }
        }
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
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
            if (value < 0) error("电脑在握手完成前断开")
            if (value == '\n'.code) break
            bytes += value.toByte()
        }
        if (bytes.size >= maximumBytes) error("电脑握手响应过长")
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}
