package com.zapret.droid.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class DomainListsTest {
    @Test
    fun classifiesQuicPacket() {
        val packet = ByteArray(20) { 0xC0.toByte() }
        assertEquals(DomainLists.UdpType.QUIC, DomainLists.classifyUdp(packet))
    }

    @Test
    fun classifiesStunPacket() {
        val packet = byteArrayOf(
            0x00, 0x01, 0x00, 0x00,
            0x21, 0x12, 0xA4.toByte(), 0x42
        ) + ByteArray(12)
        assertEquals(DomainLists.UdpType.STUN, DomainLists.classifyUdp(packet))
    }

    @Test
    fun classifiesDiscordPacket() {
        val packet = byteArrayOf(0x00, 0x01) + ByteArray(8)
        assertEquals(DomainLists.UdpType.DISCORD, DomainLists.classifyUdp(packet))
    }

    @Test
    fun unknownPacketUsesUnknownType() {
        assertEquals(DomainLists.UdpType.UNKNOWN, DomainLists.classifyUdp(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun rejectsDocumentationOnlyIpSet() {
        assertEquals(false, DomainLists.hasUsableIpRanges("203.0.113.113/32"))
        assertEquals(
            false,
            DomainLists.isIpInRanges("203.0.113.113", listOf("203.0.113.113/32"))
        )
    }

    @Test
    fun matchesIpv6IpSetRanges() {
        assertEquals(
            true,
            DomainLists.isIpInRanges("2001:4860:4860::8888", listOf("2001:4860::/32"))
        )
    }
}
