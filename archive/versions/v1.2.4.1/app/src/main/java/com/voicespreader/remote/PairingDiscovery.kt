package com.voicespreader.remote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import org.json.JSONObject

class PairingDiscovery {
    private val executor = Executors.newSingleThreadExecutor()

    fun discover(
        code: String,
        onResult: (PairingInfo) -> Unit,
        onError: (String) -> Unit,
    ) {
        executeRequest(
            request = "VSP_DISCOVER $code",
            parser = { json, sourceAddress ->
                val pairing = PairingInfo.fromDiscoveryJson(json)
                    ?: error("电脑返回了无法识别的配对信息")
                // UDP 回包的源地址一定是手机实际可达的电脑网卡。
                pairing.copy(host = sourceAddress.hostAddress ?: pairing.host)
            },
            onResult = onResult,
            onError = onError,
        )
    }

    fun locate(
        original: PairingInfo,
        onResult: (PairingInfo) -> Unit,
        onError: (String) -> Unit,
    ) {
        executeRequest(
            request = "VSP_LOCATE ${original.session}",
            parser = { json, sourceAddress ->
                val value = JSONObject(json)
                val session = value.optString("session").uppercase()
                val port = value.optInt("port")
                if (value.optInt("protocol") != 1
                    || session != original.session
                    || port !in 1..65535
                ) {
                    error("定位回包与二维码会话不匹配")
                }
                PairingInfo(
                    host = sourceAddress.hostAddress ?: original.host,
                    port = port,
                    session = original.session,
                    secret = original.secret,
                )
            },
            onResult = onResult,
            onError = onError,
        )
    }

    private fun executeRequest(
        request: String,
        parser: (String, InetAddress) -> PairingInfo,
        onResult: (PairingInfo) -> Unit,
        onError: (String) -> Unit,
    ) {
        executor.execute {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 3500
                    val data = request.toByteArray(Charsets.US_ASCII)
                    val destinations = mutableSetOf(InetAddress.getByName("255.255.255.255"))
                    NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { network ->
                        if (!network.isUp || network.isLoopback) return@forEach
                        network.interfaceAddresses.forEach { address ->
                            if (address.address is Inet4Address && address.broadcast != null) {
                                destinations += address.broadcast
                            }
                        }
                    }
                    destinations.forEach { destination ->
                        socket.send(DatagramPacket(data, data.size, destination, 39741))
                    }

                    val response = ByteArray(2048)
                    val packet = DatagramPacket(response, response.size)
                    socket.receive(packet)
                    parser(
                        String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
                        packet.address,
                    )
                }
            }.onSuccess(onResult).onFailure {
                onError(it.message ?: "没有在局域网中找到 VoiceSpreader")
            }
        }
    }

    fun close() {
        executor.shutdownNow()
    }
}
