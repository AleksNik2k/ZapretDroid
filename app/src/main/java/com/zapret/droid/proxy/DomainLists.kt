package com.zapret.droid.proxy

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Locale

object DomainLists {
    private var generalList: Set<String> = emptySet()
    private var googleList: Set<String> = emptySet()
    private var excludeList: Set<String> = emptySet()
    private val ipsetRanges = mutableListOf<IpRange>()
    private val ipsetExcludeRanges = mutableListOf<IpRange>()
    private var ipsetFilterEnabled = false
    private var gameFilterEnabled = false

    private data class IpRange(val first: Long, val last: Long)

    enum class UdpType { QUIC, STUN, DISCORD, UNKNOWN }

    fun load(context: Context) {
        generalList = loadAsset(context, "lists/list-general.txt")
        googleList = loadAsset(context, "lists/list-google.txt")
        excludeList = loadAsset(context, "lists/list-exclude.txt")

        val prefs = context.getSharedPreferences("zapret_prefs", Context.MODE_PRIVATE)
        ipsetFilterEnabled = prefs.getBoolean("ipset_filter_enabled", false)
        gameFilterEnabled = prefs.getBoolean("game_filter_enabled", false)

        generalList = generalList + prefs.getString("custom_general_domains", "").orEmpty().splitLines()
        googleList = googleList + prefs.getString("custom_google_domains", "").orEmpty().splitLines()
        excludeList = excludeList + prefs.getString("custom_exclude_domains", "").orEmpty().splitLines()

        val updatedGeneral = prefs.getString("updated_general_domains", "").orEmpty()
        val updatedGoogle = prefs.getString("updated_google_domains", "").orEmpty()
        val updatedExclude = prefs.getString("updated_exclude_domains", "").orEmpty()
        if (updatedGeneral.isNotBlank()) generalList += updatedGeneral.splitLines()
        if (updatedGoogle.isNotBlank()) googleList += updatedGoogle.splitLines()
        if (updatedExclude.isNotBlank()) excludeList += updatedExclude.splitLines()

        ipsetRanges.clear()
        ipsetRanges += parseRanges(loadAssetLines(context, "lists/ipset-all.txt"))
        ipsetRanges += parseRanges(prefs.getString("custom_ipset", "").orEmpty().splitLines())
        prefs.getString("updated_ipset", "").orEmpty().let {
            if (it.isNotBlank()) ipsetRanges += parseRanges(it.splitLines())
        }

        ipsetExcludeRanges.clear()
        ipsetExcludeRanges += parseRanges(loadAssetLines(context, "lists/ipset-exclude.txt"))
        ipsetExcludeRanges += parseRanges(prefs.getString("custom_ipset_exclude", "").orEmpty().splitLines())
        prefs.getString("updated_ipset_exclude", "").orEmpty().let {
            if (it.isNotBlank()) ipsetExcludeRanges += parseRanges(it.splitLines())
        }
    }

    private fun loadAsset(context: Context, path: String): Set<String> {
        return try {
            val set = mutableSetOf<String>()
            context.assets.open(path).use { stream ->
                BufferedReader(InputStreamReader(stream)).forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        set.add(trimmed.lowercase(Locale.ROOT).removePrefix("^"))
                    }
                }
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun loadAssetLines(context: Context, path: String): List<String> =
        loadAsset(context, path).toList()

    private fun String.splitLines(): Set<String> = lines()
        .map { it.trim().lowercase(Locale.ROOT).removePrefix("^") }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toSet()

    private fun parseRanges(lines: Iterable<String>): List<IpRange> =
        lines.mapNotNull { line ->
            val trimmed = line.trim().removeSuffix("\r")
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("^")) {
                return@mapNotNull null
            }
            val parts = trimmed.split("/")
            val address = try {
                InetAddress.getByName(parts[0]) as? Inet4Address
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            val prefix = if (parts.size > 1) parts[1].toIntOrNull() else null
            val maskBits = prefix ?: 32
            if (maskBits !in 0..32) return@mapNotNull null
            val first = address.address.fold(0L) { value, byte ->
                (value shl 8) or (byte.toLong() and 0xFF)
            }
            val size = 1L shl (32 - maskBits)
            IpRange(first, first + size - 1)
        }

    private fun isInRanges(ip: String, ranges: Iterable<IpRange>): Boolean {
        val address = try {
            InetAddress.getByName(ip) as? Inet4Address
        } catch (_: Exception) {
            null
        } ?: return false
        val value = address.address.fold(0L) { result, byte ->
            (result shl 8) or (byte.toLong() and 0xFF)
        }
        return ranges.any { value in it.first..it.last }
    }

    fun isInGeneralList(host: String): Boolean {
        val lower = host.lowercase(Locale.ROOT)
        return lower in generalList || generalList.any { lower.endsWith(".$it") }
    }

    fun isInGoogleList(host: String): Boolean {
        val lower = host.lowercase(Locale.ROOT)
        return lower in googleList || googleList.any { lower.endsWith(".$it") }
    }

    fun isExcluded(host: String): Boolean {
        val lower = host.lowercase(Locale.ROOT)
        return lower in excludeList || excludeList.any { lower.endsWith(".$it") }
    }

    fun shouldBypass(host: String): Boolean {
        if (isExcluded(host)) return false
        return isInGeneralList(host) || isInGoogleList(host)
    }

    fun shouldBypassTcp(ip: String, payload: ByteArray): Boolean {
        val host = extractHost(payload)
        if (host != null && isExcluded(host)) return false
        if (host != null && shouldBypass(host)) return true
        if (!ipsetFilterEnabled) return false
        if (isInRanges(ip, ipsetExcludeRanges)) return false
        return isInRanges(ip, ipsetRanges)
    }

    fun shouldBypassUdp(ip: String, dstPort: Int, payload: ByteArray): Boolean {
        if (isInRanges(ip, ipsetExcludeRanges)) return false
        if (ipsetFilterEnabled && isInRanges(ip, ipsetRanges)) return true
        if (dstPort == 443) return classifyUdp(payload) == UdpType.QUIC
        if (gameFilterEnabled &&
            (dstPort in 19294..19344 || dstPort in 50000..50100)
        ) return true
        return false
    }

    fun extractHost(payload: ByteArray): String? {
        extractSni(payload)?.let { return it }
        val text = try {
            String(payload, StandardCharsets.ISO_8859_1)
        } catch (_: Exception) {
            return null
        }
        val hostLine = text.lineSequence()
            .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?: return null
        return hostLine.substringAfter(':').trim()
            .substringBefore(':')
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }
    }

    private fun extractSni(payload: ByteArray): String? {
        val off = FakePackets.findSniOffset(payload)
        if (off < 0 || off + 5 > payload.size) return null
        val nameLength = ((payload[off + 3].toInt() and 0xFF) shl 8) or
            (payload[off + 4].toInt() and 0xFF)
        val nameStart = off + 5
        if (nameStart + nameLength > payload.size) return null
        return String(payload, nameStart, nameLength, Charsets.US_ASCII)
            .lowercase(Locale.ROOT)
    }

    fun classifyUdp(payload: ByteArray): UdpType {
        if (payload.size >= 20 && payload[0].toInt().and(0xF0) == 0xC0) return UdpType.QUIC
        if (payload.size >= 20 && payload[0].toInt().and(0xC0) == 0x40) return UdpType.QUIC
        if (payload.size >= 20 &&
            payload.copyOfRange(4, 8).contentEquals(byteArrayOf(
                0x21, 0x12, 0xA4.toByte(), 0x42
            ))
        ) return UdpType.STUN
        if (payload.size >= 8 &&
            (payload[0].toInt() and 0xFF) == 0 &&
            (payload[1].toInt() and 0xFF) in 0x01..0x08
        ) return UdpType.DISCORD
        return UdpType.UNKNOWN
    }
}
