package com.zapret.droid.proxy

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

object PacketUtils {
    const val DEFAULT_MTU = 1500

    fun parseIpVersion(buf: ByteArray): Int =
        if (buf.isEmpty()) 0 else (buf[0].toInt() and 0xF0) ushr 4

    fun parseIpTotalLength(buf: ByteArray): Int {
        if (buf.isEmpty()) return 0
        return when (parseIpVersion(buf)) {
            4 -> if (buf.size < 4) 0 else u16(buf, 2)
            6 -> if (buf.size < 40) 0 else 40 + u16(buf, 4)
            else -> 0
        }
    }

    fun parseIpHeaderLen(buf: ByteArray): Int {
        when (parseIpVersion(buf)) {
            4 -> return if (buf.isEmpty()) 0 else (buf[0].toInt() and 0x0F) * 4
            6 -> {
                if (buf.size < 40) return 0
                var nextHeader = buf[6].toInt() and 0xFF
                var offset = 40
                while (nextHeader in IPV6_EXTENSION_HEADERS) {
                    if (offset + 2 > buf.size) return 0
                    val extensionLength = when (nextHeader) {
                        IPV6_FRAGMENT -> 8
                        IPV6_AH -> ((buf[offset + 1].toInt() and 0xFF) + 2) * 4
                        else -> ((buf[offset + 1].toInt() and 0xFF) + 1) * 8
                    }
                    if (extensionLength < 8 || offset + extensionLength > buf.size) return 0
                    if (nextHeader == IPV6_FRAGMENT && (u16(buf, offset + 2) and 0xFFF8) != 0) {
                        return offset + extensionLength
                    }
                    nextHeader = buf[offset].toInt() and 0xFF
                    offset += extensionLength
                }
                return offset
            }
            else -> return 0
        }
    }

    fun parseIpProtocol(buf: ByteArray): Int {
        return when (parseIpVersion(buf)) {
            4 -> if (buf.size < 20) -1 else buf[9].toInt() and 0xFF
            6 -> {
                if (buf.size < 40) return -1
                var nextHeader = buf[6].toInt() and 0xFF
                var offset = 40
                while (nextHeader in IPV6_EXTENSION_HEADERS) {
                    if (offset + 2 > buf.size) return -1
                    val extensionLength = when (nextHeader) {
                        IPV6_FRAGMENT -> 8
                        IPV6_AH -> ((buf[offset + 1].toInt() and 0xFF) + 2) * 4
                        else -> ((buf[offset + 1].toInt() and 0xFF) + 1) * 8
                    }
                    if (extensionLength < 8 || offset + extensionLength > buf.size) return -1
                    if (nextHeader == IPV6_FRAGMENT && (u16(buf, offset + 2) and 0xFFF8) != 0) {
                        return buf[offset].toInt() and 0xFF
                    }
                    nextHeader = buf[offset].toInt() and 0xFF
                    offset += extensionLength
                }
                nextHeader
            }
            else -> -1
        }
    }

    fun isValidIpPacket(buf: ByteArray): Boolean {
        val version = parseIpVersion(buf)
        if (version != 4 && version != 6) return false
        val minimumHeaderLength = if (version == 4) 20 else 40
        if (buf.size < minimumHeaderLength) return false
        val totalLength = parseIpTotalLength(buf)
        val transportOffset = parseIpHeaderLen(buf)
        return transportOffset >= minimumHeaderLength &&
            totalLength >= transportOffset && totalLength <= buf.size
    }

    fun isFragment(buf: ByteArray): Boolean {
        return when (parseIpVersion(buf)) {
            4 -> {
                if (buf.size < 8) true
                else (u16(buf, 6) and 0x3FFF) != 0
            }
            6 -> {
                var nextHeader = if (buf.size >= 40) buf[6].toInt() and 0xFF else return true
                var offset = 40
                while (nextHeader in IPV6_EXTENSION_HEADERS) {
                    if (offset + 2 > buf.size) return true
                    if (nextHeader == IPV6_FRAGMENT) return true
                    val extensionLength = if (nextHeader == IPV6_AH) {
                        ((buf[offset + 1].toInt() and 0xFF) + 2) * 4
                    } else {
                        ((buf[offset + 1].toInt() and 0xFF) + 1) * 8
                    }
                    if (extensionLength < 8 || offset + extensionLength > buf.size) return true
                    nextHeader = buf[offset].toInt() and 0xFF
                    offset += extensionLength
                }
                false
            }
            else -> true
        }
    }

    fun parseIpSrc(buf: ByteArray): ByteArray =
        if (parseIpVersion(buf) == 6) buf.copyOfRange(8, 24) else buf.copyOfRange(12, 16)

    fun parseIpDst(buf: ByteArray): ByteArray =
        if (parseIpVersion(buf) == 6) buf.copyOfRange(24, 40) else buf.copyOfRange(16, 20)

    fun parseTcpSrcPort(buf: ByteArray, ipHL: Int): Int = u16(buf, ipHL)
    fun parseTcpDstPort(buf: ByteArray, ipHL: Int): Int = u16(buf, ipHL + 2)

    fun parseTcpSeq(buf: ByteArray, ipHL: Int): Long =
        ByteBuffer.wrap(buf, ipHL + 4, 4).int.toLong() and 0xFFFFFFFFL

    fun parseTcpAck(buf: ByteArray, ipHL: Int): Long =
        ByteBuffer.wrap(buf, ipHL + 8, 4).int.toLong() and 0xFFFFFFFFL

    fun parseTcpFlags(buf: ByteArray, ipHL: Int): Int = buf[ipHL + 13].toInt() and 0xFF

    fun parseTcpHeaderLen(buf: ByteArray, ipHL: Int): Int =
        ((buf[ipHL + 12].toInt() and 0xF0) ushr 4) * 4

    fun parseTcpPayload(buf: ByteArray, ipHL: Int): ByteArray {
        if (ipHL + 20 > buf.size) return ByteArray(0)
        val tcpHeaderLength = parseTcpHeaderLen(buf, ipHL)
        val start = ipHL + tcpHeaderLength
        val end = parseIpTotalLength(buf).coerceAtMost(buf.size)
        return if (tcpHeaderLength >= 20 && start <= end) buf.copyOfRange(start, end)
        else ByteArray(0)
    }

    fun parseUdpSrcPort(buf: ByteArray, ipHL: Int): Int = u16(buf, ipHL)
    fun parseUdpDstPort(buf: ByteArray, ipHL: Int): Int = u16(buf, ipHL + 2)
    fun parseUdpLength(buf: ByteArray, ipHL: Int): Int = u16(buf, ipHL + 4)

    fun parseUdpPayload(buf: ByteArray, ipHL: Int): ByteArray {
        if (ipHL + 8 > buf.size) return ByteArray(0)
        val udpLength = u16(buf, ipHL + 4)
        val end = minOf(parseIpTotalLength(buf), ipHL + udpLength, buf.size)
        return if (end >= ipHL + 8) buf.copyOfRange(ipHL + 8, end) else ByteArray(0)
    }

    fun buildIpTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        windowSize: Int = 65535,
        payload: ByteArray = ByteArray(0),
        ttl: Int = 64
    ): ByteArray {
        val tcpLength = 20 + payload.size
        val ipHeaderLength = if (srcIp.size == 16 && dstIp.size == 16) 40 else 20
        require(srcIp.size == dstIp.size && (srcIp.size == 4 || srcIp.size == 16))
        val packet = ByteArray(ipHeaderLength + tcpLength)
        writeIpHeader(packet, srcIp, dstIp, tcpLength, 6, ttl)

        val offset = ipHeaderLength
        putU16(packet, offset, srcPort)
        putU16(packet, offset + 2, dstPort)
        putU32(packet, offset + 4, seq)
        putU32(packet, offset + 8, ack)
        packet[offset + 12] = 0x50
        packet[offset + 13] = flags.toByte()
        putU16(packet, offset + 14, windowSize)
        payload.copyInto(packet, offset + 20)
        putU16(packet, offset + 16, transportChecksum(packet, srcIp, dstIp, offset, tcpLength, 6))
        return packet
    }

    fun buildIpUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        ttl: Int = 64
    ): ByteArray {
        val udpLength = 8 + payload.size
        val ipHeaderLength = if (srcIp.size == 16 && dstIp.size == 16) 40 else 20
        require(srcIp.size == dstIp.size && (srcIp.size == 4 || srcIp.size == 16))
        val packet = ByteArray(ipHeaderLength + udpLength)
        writeIpHeader(packet, srcIp, dstIp, udpLength, 17, ttl)

        val offset = ipHeaderLength
        putU16(packet, offset, srcPort)
        putU16(packet, offset + 2, dstPort)
        putU16(packet, offset + 4, udpLength)
        payload.copyInto(packet, offset + 8)
        var checksum = transportChecksum(packet, srcIp, dstIp, offset, udpLength, 17)
        if (srcIp.size == 16 && checksum == 0) checksum = 0xFFFF
        putU16(packet, offset + 6, checksum)
        return packet
    }

    fun fragmentIpPacket(packet: ByteArray, mtu: Int): List<ByteArray> {
        require(mtu >= 1280)
        if (packet.size <= mtu) return listOf(packet)
        require(isValidIpPacket(packet))
        return when (parseIpVersion(packet)) {
            4 -> fragmentIpv4(packet, mtu)
            6 -> fragmentIpv6(packet, mtu)
            else -> throw IllegalArgumentException("Only IPv4 and IPv6 packets can be fragmented")
        }
    }

    private fun fragmentIpv4(packet: ByteArray, mtu: Int): List<ByteArray> {
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        val fragmentPayloadSize = ((mtu - headerLength) / 8) * 8
        require(fragmentPayloadSize >= 8)
        val payloadLength = packet.size - headerLength
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payloadLength) {
            val length = minOf(fragmentPayloadSize, payloadLength - offset)
            val moreFragments = offset + length < payloadLength
            val fragment = ByteArray(headerLength + length)
            packet.copyInto(fragment, 0, 0, headerLength)
            packet.copyInto(fragment, headerLength, headerLength + offset, headerLength + offset + length)
            putU16(fragment, 2, fragment.size)
            putU16(
                fragment,
                6,
                (if (moreFragments) 0x2000 else 0) or (offset / 8)
            )
            putU16(fragment, 10, 0)
            putU16(fragment, 10, checksum(fragment, 0, headerLength))
            result += fragment
            offset += length
        }
        return result
    }

    private fun fragmentIpv6(packet: ByteArray, mtu: Int): List<ByteArray> {
        val fragmentPayloadSize = ((mtu - 48) / 8) * 8
        require(fragmentPayloadSize >= 8)
        val payloadLength = packet.size - 40
        val fragmentId = ipv6FragmentId.getAndIncrement()
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payloadLength) {
            val length = minOf(fragmentPayloadSize, payloadLength - offset)
            val moreFragments = offset + length < payloadLength
            val fragment = ByteArray(48 + length)
            packet.copyInto(fragment, 0, 0, 40)
            fragment[6] = IPV6_FRAGMENT.toByte()
            putU16(fragment, 4, 8 + length)
            fragment[40] = packet[6]
            putU16(fragment, 42, (offset and 0xFFF8) or if (moreFragments) 1 else 0)
            putU32(fragment, 44, fragmentId.toLong() and 0xFFFF_FFFFL)
            packet.copyInto(fragment, 48, 40 + offset, 40 + offset + length)
            result += fragment
            offset += length
        }
        return result
    }

    private fun writeIpHeader(
        packet: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        transportLength: Int,
        protocol: Int,
        ttl: Int
    ) {
        if (srcIp.size == 4) {
            val totalLength = 20 + transportLength
            packet[0] = 0x45
            putU16(packet, 2, totalLength)
            putU16(packet, 4, ipv4Identification.getAndIncrement() and 0xFFFF)
            packet[6] = 0x40
            packet[8] = ttl.toByte()
            packet[9] = protocol.toByte()
            srcIp.copyInto(packet, 12)
            dstIp.copyInto(packet, 16)
            putU16(packet, 10, checksum(packet, 0, 20))
        } else {
            packet[0] = 0x60
            putU16(packet, 4, transportLength)
            packet[6] = protocol.toByte()
            packet[7] = ttl.toByte()
            srcIp.copyInto(packet, 8)
            dstIp.copyInto(packet, 24)
        }
    }

    private fun transportChecksum(
        packet: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        transportOffset: Int,
        transportLength: Int,
        protocol: Int
    ): Int {
        val pseudo = if (srcIp.size == 4) {
            ByteArray(12 + transportLength).also {
                srcIp.copyInto(it, 0)
                dstIp.copyInto(it, 4)
                it[9] = protocol.toByte()
                putU16(it, 10, transportLength)
                packet.copyInto(it, 12, transportOffset, transportOffset + transportLength)
            }
        } else {
            ByteArray(40 + transportLength).also {
                srcIp.copyInto(it, 0)
                dstIp.copyInto(it, 16)
                putU32(it, 32, transportLength.toLong())
                it[39] = protocol.toByte()
                packet.copyInto(it, 40, transportOffset, transportOffset + transportLength)
            }
        }
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun checksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i + 1 < offset + length) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + length) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun putU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 8).toByte()
        buf[offset + 1] = value.toByte()
    }

    private fun putU32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    fun ipToBytes(ip: String): ByteArray = InetAddress.getByName(ip).address
    fun bytesToIp(bytes: ByteArray): String = InetAddress.getByAddress(bytes).hostAddress ?: ""

    private val IPV6_FRAGMENT = 44
    private val IPV6_AH = 51
    private val IPV6_EXTENSION_HEADERS = setOf(0, 43, 44, 60, 51)
    private val ipv4Identification = AtomicInteger()
    private val ipv6FragmentId = AtomicInteger()

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10
    const val FLAG_URG = 0x20
}
