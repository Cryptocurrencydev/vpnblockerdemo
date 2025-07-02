// File: DnsVpnService.kt
package com.example.vpnblockerdemo

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class DnsVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val blockedDomains = listOf("www.xvideos.com")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        vpnInterface = Builder()
            .addAddress("10.0.0.2", 32)
            .addDnsServer("8.8.8.8")
            .addRoute("8.8.8.8", 32) // 🛠 Only DNS through VPN
            .allowBypass()
            .setSession("VPNBlockerDemo")
            .establish()

        LogHelper.log("✅ VPN established: ${vpnInterface != null}")

        vpnInterface?.let { vpn ->
            val input = FileInputStream(vpn.fileDescriptor)
            val output = FileOutputStream(vpn.fileDescriptor)

            CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(32767)
                while (true) {
                    val length = input.read(buffer)
                    if (length <= 0) continue
                    val packet = buffer.copyOf(length)

                    if (!isUdpPort53(packet)) continue

                    val domain = parseQueryDomain(packet) ?: continue
                    LogHelper.log("🌐 DNS Query for: $domain")

                    val shouldBlock = blockedDomains.any { domain == it || domain.endsWith(".$it") }

                    val response: ByteArray? = if (shouldBlock) {
                        LogHelper.log("🚫 Blocking DNS for $domain")
                        createFakeDnsResponsePacket(packet)
                    } else {
                        LogHelper.log("✅ Allowing DNS for $domain")
                        forwardDnsToRealServer(packet)
                    }
                    response?.let { output.write(it) }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }

    private fun isUdpPort53(packet: ByteArray): Boolean {
        if (packet.size < 28) return false
        val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return false
        val destPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLen + 3].toInt() and 0xFF)
        return destPort == 53
    }

    private fun parseQueryDomain(packet: ByteArray): String? {
        return try {
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            val dnsStart = ipHeaderLen + 8
            var index = dnsStart + 12
            val domainParts = mutableListOf<String>()

            while (index < packet.size) {
                val len = packet[index].toInt() and 0xFF
                if (len == 0) break
                if (len > 63 || index + 1 + len > packet.size) return null
                val part = packet.copyOfRange(index + 1, index + 1 + len)
                domainParts.add(part.toString(Charsets.UTF_8))
                index += len + 1
            }

            domainParts.joinToString(".")
        } catch (e: Exception) {
            LogHelper.log("❌ parseQueryDomain exception: ${e.message}")
            null
        }
    }

    private fun createFakeDnsResponsePacket(request: ByteArray): ByteArray {
        val ipHeaderLen = (request[0].toInt() and 0x0F) * 4
        val udpStart = ipHeaderLen
        val dnsStart = udpStart + 8
        val dnsLength = request.size - dnsStart
        val query = request.copyOfRange(dnsStart, dnsStart + dnsLength)

        val response = query.copyOf()
        response[2] = 0x81.toByte() // QR=1
        response[3] = 0x83.toByte() // NXDOMAIN
        response[7] = 0x00.toByte() // ANCOUNT=0

        return buildUdpIpPacket(request, response)
    }

    private fun forwardDnsToRealServer(packet: ByteArray): ByteArray? {
        return try {
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            val dnsStart = ipHeaderLen + 8
            val dnsQuery = packet.copyOfRange(dnsStart, packet.size)

            val channel = DatagramChannel.open()
            channel.configureBlocking(true)
            val socket = channel.socket()

            val protected = protect(socket)
            LogHelper.log("🛡️ protect() returned: $protected")
            if (!protected) {
                socket.close()
                return null
            }

            val serverAddress = InetSocketAddress("8.8.8.8", 53)
            channel.connect(serverAddress)
            channel.write(ByteBuffer.wrap(dnsQuery))

            val buffer = ByteBuffer.allocate(512)
            val timeoutMillis = 2000
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMillis) {
                val len = channel.read(buffer)
                if (len > 0) {
                    buffer.flip()
                    val dnsResponse = ByteArray(len)
                    buffer.get(dnsResponse)

                    LogHelper.log("📦 Got DNS response from 8.8.8.8 (${dnsResponse.size} bytes)")
                    LogHelper.log(
                        "📄 HEX: ${
                            dnsResponse.joinToString(" ") {
                                String.format(
                                    "%02X",
                                    it
                                )
                            }
                        }"
                    )

                    channel.close()
                    return buildUdpIpPacket(packet, dnsResponse)
                }
            }

            channel.close()
            LogHelper.log("❌ Timeout: No DNS response from real server")
            null
        } catch (e: Exception) {
            LogHelper.log("❌ DNS forward error: ${e.message}")
            null
        }

    }

    private fun rebuildUdpIp(request: ByteArray, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLen = (request[0].toInt() and 0x0F) * 4
        val udpStart = ipHeaderLen

        val srcPort =
            ((request[udpStart].toInt() and 0xFF) shl 8) or (request[udpStart + 1].toInt() and 0xFF)
        val dstPort =
            ((request[udpStart + 2].toInt() and 0xFF) shl 8) or (request[udpStart + 3].toInt() and 0xFF)

        val udp = ByteArray(8 + dnsResponse.size)
        udp[0] = ((dstPort shr 8) and 0xFF).toByte()
        udp[1] = (dstPort and 0xFF).toByte()
        udp[2] = ((srcPort shr 8) and 0xFF).toByte()
        udp[3] = (srcPort and 0xFF).toByte()
        udp[4] = ((udp.size shr 8) and 0xFF).toByte()
        udp[5] = (udp.size and 0xFF).toByte()
        udp[6] = 0
        udp[7] = 0
        System.arraycopy(dnsResponse, 0, udp, 8, dnsResponse.size)

        val srcIP = request.copyOfRange(12, 16)
        val dstIP = request.copyOfRange(16, 20)

        val totalLen = 20 + udp.size
        val ip = ByteArray(totalLen)
        ip[0] = 0x45.toByte()
        ip[1] = 0
        ip[2] = (totalLen shr 8).toByte()
        ip[3] = (totalLen and 0xFF).toByte()
        ip[4] = 0
        ip[5] = 0
        ip[6] = 0
        ip[7] = 0
        ip[8] = 64
        ip[9] = 17
        ip[10] = 0
        ip[11] = 0
        System.arraycopy(dstIP, 0, ip, 12, 4)
        System.arraycopy(srcIP, 0, ip, 16, 4)
        System.arraycopy(udp, 0, ip, 20, udp.size)

        val checksum = ipChecksum(ip.copyOfRange(0, 20))
        ip[10] = (checksum shr 8).toByte()
        ip[11] = (checksum and 0xFF).toByte()

        return ip
    }


    private fun ipChecksum(header: ByteArray): Int {
        var sum = 0
        for (i in header.indices step 2) {
            val part = ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            sum += part
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun buildUdpIpPacket(original: ByteArray, payload: ByteArray): ByteArray {
        val ipHeaderLen = (original[0].toInt() and 0x0F) * 4
        val udpStart = ipHeaderLen

        val srcPort = ((original[udpStart].toInt() and 0xFF) shl 8) or (original[udpStart + 1].toInt() and 0xFF)
        val dstPort = ((original[udpStart + 2].toInt() and 0xFF) shl 8) or (original[udpStart + 3].toInt() and 0xFF)

        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen

        val srcIP = original.copyOfRange(16, 20)
        val dstIP = original.copyOfRange(12, 16)

        val ip = ByteArray(totalLen)

        // IP Header
        ip[0] = 0x45.toByte()
        ip[1] = 0
        ip[2] = (totalLen shr 8).toByte()
        ip[3] = (totalLen and 0xFF).toByte()
        ip[4] = 0
        ip[5] = 0
        ip[6] = 0
        ip[7] = 0
        ip[8] = 64
        ip[9] = 17 // UDP
        ip[10] = 0
        ip[11] = 0
        System.arraycopy(srcIP, 0, ip, 12, 4)
        System.arraycopy(dstIP, 0, ip, 16, 4)

        val checksum = ipChecksum(ip.copyOfRange(0, 20))
        ip[10] = (checksum shr 8).toByte()
        ip[11] = (checksum and 0xFF).toByte()

        // UDP Header
        ip[20] = (dstPort shr 8).toByte()
        ip[21] = (dstPort and 0xFF).toByte()
        ip[22] = (srcPort shr 8).toByte()
        ip[23] = (srcPort and 0xFF).toByte()
        ip[24] = (udpLen shr 8).toByte()
        ip[25] = (udpLen and 0xFF).toByte()
        ip[26] = 0  // UDP checksum = 0 is valid for IPv4
        ip[27] = 0

        // Payload
        System.arraycopy(payload, 0, ip, 28, payload.size)

        return ip
    }
}