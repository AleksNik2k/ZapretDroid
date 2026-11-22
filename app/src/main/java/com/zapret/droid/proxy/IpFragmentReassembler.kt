package com.zapret.droid.proxy

import java.util.TreeMap

class IpFragmentReassembler {
    private data class FragmentKey(
        val version: Int,
        val source: String,
        val destination: String,
        val protocol: Int,
        val id: Long
    )

    private data class Fragment(
        val key: FragmentKey,
        val offset: Int,
        val more: Boolean,
        val payload: ByteArray,
        val header: ByteArray?,
        val previousNextHeaderOffset: Int,
        val nextHeader: Int
    )

    private class Assembly(val createdAt: Long) {
        val fragments = TreeMap<Int, ByteArray>()
        var header: ByteArray? = null
        var previousNextHeaderOffset = -1
        var nextHeader = -1
        var finalLength: Int? = null
        var totalFragmentBytes = 0
    }

    private val assemblies = LinkedHashMap<FragmentKey, Assembly>()

    @Synchronized
    fun accept(packet: ByteArray): ByteArray? {
        val fragment = parseFragment(packet) ?: return null
        if (fragment.payload.isEmpty() || (fragment.more && fragment.payload.size % 8 != 0)) {
            assemblies.remove(fragment.key)
            return null
        }
        val maximumFragmentEnd = if (fragment.key.version == 4) {
            MAX_PACKET_SIZE - (fragment.header?.size ?: IPV4_MIN_HEADER_SIZE)
        } else {
            MAX_IPV6_PAYLOAD_SIZE
        }
        val fragmentEnd = fragment.offset + fragment.payload.size
        if (fragment.offset < 0 || fragmentEnd > maximumFragmentEnd) {
            assemblies.remove(fragment.key)
            return null
        }

        expireOldAssemblies()
        var assembly = assemblies[fragment.key]
        if (assembly == null) {
            if (assemblies.size >= MAX_ASSEMBLIES) {
                assemblies.entries.iterator().run {
                    if (hasNext()) {
                        next()
                        remove()
                    }
                }
            }
            assembly = Assembly(System.currentTimeMillis())
            assemblies[fragment.key] = assembly
        }

        if (fragment.offset == 0) {
            val existingHeader = assembly.header
            if (existingHeader != null && !existingHeader.contentEquals(fragment.header)) {
                assemblies.remove(fragment.key)
                return null
            }
            assembly.header = fragment.header
            assembly.previousNextHeaderOffset = fragment.previousNextHeaderOffset
            assembly.nextHeader = fragment.nextHeader
        }

        val duplicate = assembly.fragments[fragment.offset]
        if (duplicate != null) {
            if (!duplicate.contentEquals(fragment.payload)) {
                assemblies.remove(fragment.key)
                return null
            }
        } else {
            if (assembly.fragments.size >= MAX_FRAGMENTS_PER_ASSEMBLY) {
                assemblies.remove(fragment.key)
                return null
            }
            val overlapping = assembly.fragments.entries.any { (offset, payload) ->
                val end = offset + payload.size
                fragment.offset < end && fragmentEnd > offset
            }
            if (overlapping) {
                assemblies.remove(fragment.key)
                return null
            }
            assembly.fragments[fragment.offset] = fragment.payload
            assembly.totalFragmentBytes += fragment.payload.size
        }
        if (assembly.totalFragmentBytes > maximumFragmentEnd) {
            assemblies.remove(fragment.key)
            return null
        }
        if (!fragment.more) {
            if (assembly.finalLength != null && assembly.finalLength != fragmentEnd) {
                assemblies.remove(fragment.key)
                return null
            }
            assembly.finalLength = fragmentEnd
        }

        val header = assembly.header ?: return null
        val finalLength = assembly.finalLength ?: return null
        if (fragment.key.version == 6 &&
            header.size - IPV6_MIN_HEADER_SIZE + finalLength > MAX_IPV6_PAYLOAD_SIZE
        ) {
            assemblies.remove(fragment.key)
            return null
        }
        val assembledPayload = ByteArray(finalLength)
        var expectedOffset = 0
        for ((offset, payload) in assembly.fragments) {
            if (offset != expectedOffset) return null
            payload.copyInto(assembledPayload, offset)
            expectedOffset += payload.size
        }
        if (expectedOffset != finalLength) return null

        val result = if (fragment.key.version == 4) {
            buildIpv4Packet(header, assembledPayload)
        } else {
            buildIpv6Packet(header, assembledPayload, assembly.previousNextHeaderOffset, assembly.nextHeader)
        }
        assemblies.remove(fragment.key)
        return result
    }

    private fun parseFragment(packet: ByteArray): Fragment? {
        if (!PacketUtils.isValidIpPacket(packet) || !PacketUtils.isFragment(packet)) return null
        val version = PacketUtils.parseIpVersion(packet)
        val totalLength = PacketUtils.parseIpTotalLength(packet)
        return when (version) {
            4 -> {
                val headerLength = (packet[0].toInt() and 0x0F) * 4
                val fragmentField = u16(packet, 6)
                val offset = (fragmentField and 0x1FFF) * 8
                val payload = packet.copyOfRange(headerLength, totalLength)
                Fragment(
                    key = FragmentKey(
                        version = 4,
                        source = PacketUtils.bytesToIp(PacketUtils.parseIpSrc(packet)),
                        destination = PacketUtils.bytesToIp(PacketUtils.parseIpDst(packet)),
                        protocol = packet[9].toInt() and 0xFF,
                        id = u16(packet, 4).toLong()
                    ),
                    offset = offset,
                    more = fragmentField and 0x2000 != 0,
                    payload = payload,
                    header = if (offset == 0) packet.copyOfRange(0, headerLength) else null,
                    previousNextHeaderOffset = -1,
                    nextHeader = -1
                )
            }
            6 -> parseIpv6Fragment(packet, totalLength)
            else -> null
        }
    }

    private fun parseIpv6Fragment(packet: ByteArray, totalLength: Int): Fragment? {
        var nextHeader = packet[6].toInt() and 0xFF
        var previousNextHeaderOffset = 6
        var offset = IPV6_MIN_HEADER_SIZE
        while (nextHeader in IPV6_EXTENSION_HEADERS) {
            if (offset + 2 > totalLength) return null
            if (nextHeader == IPV6_FRAGMENT_HEADER) {
                if (offset + 8 > totalLength) return null
                val fragmentField = u16(packet, offset + 2)
                val fragmentOffset = fragmentField and 0xFFF8
                val fragmentNextHeader = packet[offset].toInt() and 0xFF
                val payloadStart = offset + 8
                return Fragment(
                    key = FragmentKey(
                        version = 6,
                        source = PacketUtils.bytesToIp(PacketUtils.parseIpSrc(packet)),
                        destination = PacketUtils.bytesToIp(PacketUtils.parseIpDst(packet)),
                        protocol = fragmentNextHeader,
                        id = u32(packet, offset + 4)
                    ),
                    offset = fragmentOffset,
                    more = fragmentField and 1 != 0,
                    payload = packet.copyOfRange(payloadStart, totalLength),
                    header = if (fragmentOffset == 0) packet.copyOfRange(0, offset) else null,
                    previousNextHeaderOffset = previousNextHeaderOffset,
                    nextHeader = fragmentNextHeader
                )
            }
            val extensionLength = when (nextHeader) {
                IPV6_AH_HEADER -> ((packet[offset + 1].toInt() and 0xFF) + 2) * 4
                else -> ((packet[offset + 1].toInt() and 0xFF) + 1) * 8
            }
            if (extensionLength < 8 || offset + extensionLength > totalLength) return null
            previousNextHeaderOffset = offset
            nextHeader = packet[offset].toInt() and 0xFF
            offset += extensionLength
        }
        return null
    }

    private fun buildIpv4Packet(header: ByteArray, payload: ByteArray): ByteArray {
        val packet = header + payload
        putU16(packet, 2, packet.size)
        putU16(packet, 6, u16(packet, 6) and 0x4000)
        putU16(packet, 10, 0)
        putU16(packet, 10, checksum(packet, 0, header.size))
        return packet
    }

    private fun buildIpv6Packet(
        header: ByteArray,
        payload: ByteArray,
        previousNextHeaderOffset: Int,
        nextHeader: Int
    ): ByteArray {
        val packet = header + payload
        packet[previousNextHeaderOffset] = nextHeader.toByte()
        putU16(packet, 4, packet.size - IPV6_MIN_HEADER_SIZE)
        return packet
    }

    private fun expireOldAssemblies() {
        val cutoff = System.currentTimeMillis() - ASSEMBLY_TIMEOUT_MS
        assemblies.entries.removeAll { it.value.createdAt < cutoff }
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

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private val IPV6_MIN_HEADER_SIZE = 40
    private val IPV4_MIN_HEADER_SIZE = 20
    private val IPV6_FRAGMENT_HEADER = 44
    private val IPV6_AH_HEADER = 51
    private val IPV6_EXTENSION_HEADERS = setOf(0, 43, 44, 60, 51)

    private companion object {
        const val MAX_PACKET_SIZE = 65_535
        const val MAX_IPV6_PAYLOAD_SIZE = 65_535
        const val MAX_ASSEMBLIES = 128
        const val MAX_FRAGMENTS_PER_ASSEMBLY = 128
        const val ASSEMBLY_TIMEOUT_MS = 30_000L
    }
}
