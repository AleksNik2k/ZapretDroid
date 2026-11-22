package com.zapret.droid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketUtilsTest {
    @Test
    fun buildsValidIpv4TcpPacketWithChecksums() {
        val source = byteArrayOf(192.toByte(), 0, 2, 10)
        val destination = byteArrayOf(198.toByte(), 51, 100, 20)
        val packet = PacketUtils.buildIpTcpPacket(
            srcIp = source,
            dstIp = destination,
            srcPort = 12345,
            dstPort = 443,
            seq = 0xFFFF_FFFEL,
            ack = 7,
            flags = PacketUtils.FLAG_ACK,
            payload = byteArrayOf(1, 2, 3)
        )

        assertTrue(PacketUtils.isValidIpPacket(packet))
        assertEquals(4, PacketUtils.parseIpVersion(packet))
        assertEquals(6, PacketUtils.parseIpProtocol(packet))
        assertEquals(0, checksum(packet, 0, 20))
        assertEquals(0, ipv4TransportChecksum(packet, 6))
    }

    @Test
    fun buildsValidIpv6UdpPacketWithChecksum() {
        val source = PacketUtils.ipToBytes("2001:4860::1")
        val destination = PacketUtils.ipToBytes("2001:4860::2")
        val packet = PacketUtils.buildIpUdpPacket(
            srcIp = source,
            dstIp = destination,
            srcPort = 53000,
            dstPort = 53,
            payload = byteArrayOf(1, 2, 3, 4)
        )

        assertTrue(PacketUtils.isValidIpPacket(packet))
        assertEquals(6, PacketUtils.parseIpVersion(packet))
        assertEquals(17, PacketUtils.parseIpProtocol(packet))
        assertEquals(0, ipv6TransportChecksum(packet, 17))
    }

    @Test
    fun parsesIpv6ExtensionHeaderAndRejectsFragments() {
        val packet = ByteArray(40 + 8 + 20)
        packet[0] = 0x60
        packet[4] = 0
        packet[5] = 28
        packet[6] = 60
        packet[7] = 64
        packet[40] = 6
        packet[41] = 0
        packet[60] = 0x50

        assertTrue(PacketUtils.isValidIpPacket(packet))
        assertEquals(48, PacketUtils.parseIpHeaderLen(packet))
        assertEquals(6, PacketUtils.parseIpProtocol(packet))
        assertFalse(PacketUtils.isFragment(packet))

        packet[40] = 44
        assertTrue(PacketUtils.isFragment(packet))
    }

    @Test
    fun rejectsMalformedIpLengthsAndDetectsIpv4Fragments() {
        val packet = PacketUtils.buildIpUdpPacket(
            srcIp = byteArrayOf(192.toByte(), 0, 2, 1),
            dstIp = byteArrayOf(198.toByte(), 51, 100, 1),
            srcPort = 1000,
            dstPort = 1001,
            payload = byteArrayOf(1)
        )
        packet[2] = 0
        packet[3] = 19
        assertFalse(PacketUtils.isValidIpPacket(packet))

        val fragment = PacketUtils.buildIpUdpPacket(
            srcIp = byteArrayOf(192.toByte(), 0, 2, 1),
            dstIp = byteArrayOf(198.toByte(), 51, 100, 1),
            srcPort = 1000,
            dstPort = 1001,
            payload = byteArrayOf(1)
        )
        fragment[6] = 0x20
        assertTrue(PacketUtils.isFragment(fragment))
    }

    @Test
    fun reassemblesOutOfOrderIpv4Fragments() {
        val payload = ByteArray(2000) { it.toByte() }
        val packet = PacketUtils.buildIpUdpPacket(
            srcIp = byteArrayOf(192.toByte(), 0, 2, 1),
            dstIp = byteArrayOf(198.toByte(), 51, 100, 1),
            srcPort = 1000,
            dstPort = 1001,
            payload = payload
        )
        val fragments = PacketUtils.fragmentIpPacket(packet, 1280)
        val reassembler = IpFragmentReassembler()

        assertTrue(fragments.size > 1)
        assertEquals(null, reassembler.accept(fragments.last()))
        val result = reassembler.accept(fragments.first())!!

        assertTrue(PacketUtils.isValidIpPacket(result))
        assertEquals(0, checksum(result, 0, 20))
        assertEquals(payload.toList(), PacketUtils.parseUdpPayload(result, 20).toList())
    }

    @Test
    fun reassemblesOutOfOrderIpv6Fragments() {
        val payload = ByteArray(2000) { (it * 3).toByte() }
        val packet = PacketUtils.buildIpUdpPacket(
            srcIp = PacketUtils.ipToBytes("2001:4860::1"),
            dstIp = PacketUtils.ipToBytes("2001:4860::2"),
            srcPort = 1000,
            dstPort = 1001,
            payload = payload
        )
        val fragments = PacketUtils.fragmentIpPacket(packet, 1280)
        val reassembler = IpFragmentReassembler()

        assertTrue(fragments.size > 1)
        assertEquals(null, reassembler.accept(fragments.last()))
        val result = reassembler.accept(fragments.first())!!

        assertTrue(PacketUtils.isValidIpPacket(result))
        assertEquals(17, PacketUtils.parseIpProtocol(result))
        assertEquals(payload.toList(), PacketUtils.parseUdpPayload(result, 40).toList())
    }

    private fun ipv4TransportChecksum(packet: ByteArray, protocol: Int): Int {
        val length = packet.size - 20
        val pseudo = ByteArray(12 + length)
        packet.copyInto(pseudo, 0, 12, 20)
        pseudo[9] = protocol.toByte()
        pseudo[10] = (length ushr 8).toByte()
        pseudo[11] = length.toByte()
        packet.copyInto(pseudo, 12, 20, packet.size)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun ipv6TransportChecksum(packet: ByteArray, protocol: Int): Int {
        val length = packet.size - 40
        val pseudo = ByteArray(40 + length)
        packet.copyInto(pseudo, 0, 8, 40)
        pseudo[35] = length.toByte()
        pseudo[39] = protocol.toByte()
        packet.copyInto(pseudo, 40, 40, packet.size)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        while (index + 1 < offset + length) {
            sum += ((data[index].toInt() and 0xFF) shl 8) or
                (data[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < offset + length) sum += (data[index].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }
}
