package com.voicespreader.remote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

class PairingDiscovery {
    private val executor = Executors.newSingleThreadExecutor()

    fun discover(
        code: String,
        onResult: (PairingInfo) -> Unit,
        onError: (String) -> Unit,
    ) {
        executor.execute {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 3500
                    val data = "VSP_DISCOVER $code".toByteArray(Charsets.US_ASCII)
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
                    val pairing = PairingInfo.fromDiscoveryJson(
                        String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
                    ) ?: error("电脑返回了无法识别的配对信息")
                    // UDP 回包的源地址一定是手机实际可达的电脑网卡，可避开虚拟网卡选错地址。
                    pairing.copy(host = packet.address.hostAddress ?: pairing.host)
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
