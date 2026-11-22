package com.zapret.droid.proxy

import android.content.Context
import com.zapret.droid.strategies.*
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket

class DpiBypass(private val context: Context, private val strategy: Strategy) {

    init {
        FakePackets.load(context)
    }

    fun applyOnConnect(socket: Socket, clientData: ByteArray, out: OutputStream) {
        socket.tcpNoDelay = true

        val methods = strategy.dpiDesync

        when {
            methods.contains(DesyncMethod.SYNDATA) ->
                sendSplit(out, clientData, 1)

            methods.contains(DesyncMethod.MULTIDISORDER) ->
                sendDisorder(socket, out, clientData, getSplitPosition(clientData, strategy.splitPos))

            methods.contains(DesyncMethod.HOSTFAKESPLIT) ->
                sendHostFakeSplit(socket, out, clientData, getSplitPosition(clientData, strategy.splitPos))

            methods.contains(DesyncMethod.FAKE) && methods.contains(DesyncMethod.MULTISPLIT) -> {
                sendFakePackets(socket, out)
                sendSplitWithSeqovl(socket, out, clientData, getSplitPosition(clientData, strategy.splitPos))
            }

            methods.contains(DesyncMethod.FAKE) && methods.contains(DesyncMethod.FAKEDSPLIT) -> {
                sendFakePackets(socket, out)
                sendDisorder(socket, out, clientData, getSplitPosition(clientData, strategy.splitPos))
            }

            methods.contains(DesyncMethod.MULTISPLIT) ->
                sendSplitWithSeqovl(socket, out, clientData, getSplitPosition(clientData, strategy.splitPos))

            methods.contains(DesyncMethod.FAKE) -> {
                sendFakePackets(socket, out)
                out.write(clientData)
                out.flush()
            }

            else -> {
                out.write(clientData)
                out.flush()
            }
        }
    }

    fun applyOnUdpSend(socket: DatagramSocket, realDst: InetSocketAddress, payload: ByteArray) {
        if (strategy.dpiDesync.contains(DesyncMethod.FAKE)) {
            val fakeData = getFakeUdpData(payload)
            repeat(strategy.dpiDesyncRepeats) {
                try {
                    setUdpTtl(socket, strategy.fakeTtl)
                    socket.send(DatagramPacket(fakeData, fakeData.size, realDst))
                } catch (_: Exception) {}
            }
            setUdpTtl(socket, 64)
        }
        socket.send(DatagramPacket(payload, payload.size, realDst))
    }

    private fun setUdpTtl(socket: DatagramSocket, ttl: Int) {
        try {
            val implField = DatagramSocket::class.java.getDeclaredField("impl")
            implField.isAccessible = true
            val impl = implField.get(socket)
            val setOptMethod = impl.javaClass.getMethod("setOption", Int::class.java, Any::class.java)
            setOptMethod.invoke(impl, 0x03, ttl)
        } catch (_: Exception) {}
    }

    private fun setTtl(socket: Socket, ttl: Int) {
        try {
            // Reflection-based IP_TTL for Android (no StandardSocketOptions available pre-API24)
            val implField = Socket::class.java.getDeclaredField("impl")
            implField.isAccessible = true
            val impl = implField.get(socket)
            val setOptMethod = impl.javaClass.getMethod("setOption", Int::class.java, Any::class.java)
            setOptMethod.invoke(impl, 0x03 /* IP_TTL */, ttl)
        } catch (_: Exception) {}
    }

    private fun sendFakePackets(socket: Socket, out: OutputStream) {
        val fakeData = getFakeTcpData()
        repeat(strategy.dpiDesyncRepeats) {
            try {
                setTtl(socket, strategy.fakeTtl)
                out.write(fakeData)
                out.flush()
            } catch (_: Exception) {}
        }
        setTtl(socket, 64)
    }

    private fun sendSplit(out: OutputStream, data: ByteArray, splitAt: Int) {
        val pos = splitAt.coerceIn(1, data.size - 1)
        out.write(data, 0, pos)
        out.flush()
        Thread.sleep(1)
        out.write(data, pos, data.size - pos)
        out.flush()
    }

    private fun sendSplitWithSeqovl(socket: Socket, out: OutputStream, data: ByteArray, splitAt: Int) {
        val overlapSize = strategy.seqovl.coerceIn(1, 4096)
        val fakeData = getFakeTcpData()
        val fakeChunk = fakeData.copyOf(overlapSize.coerceAtMost(fakeData.size))
        try {
            setTtl(socket, strategy.fakeTtl)
            out.write(fakeChunk)
            out.flush()
            setTtl(socket, 64)
        } catch (_: Exception) {}

        val pos = splitAt.coerceIn(1, data.size - 1)
        out.write(data, 0, pos)
        out.flush()
        Thread.sleep(1)
        out.write(data, pos, data.size - pos)
        out.flush()
    }

    private fun sendDisorder(socket: Socket, out: OutputStream, data: ByteArray, splitAt: Int) {
        val pos = splitAt.coerceIn(1, data.size - 1)
        val first = data.copyOfRange(0, pos)
        val second = data.copyOfRange(pos, data.size)
        try {
            setTtl(socket, strategy.fakeTtl)
            out.write(second)
            out.flush()
            setTtl(socket, 64)
        } catch (_: Exception) {}
        Thread.sleep(1)
        out.write(first)
        out.flush()
        Thread.sleep(1)
        out.write(second)
        out.flush()
    }

    private fun sendHostFakeSplit(socket: Socket, out: OutputStream, data: ByteArray, splitAt: Int) {
        try {
            setTtl(socket, strategy.fakeTtl)
            val host = strategy.hostFakeHost
            val request = if (strategy.hostFakeAltOrder) {
                "GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            } else {
                "GET / HTTP/1.1\r\nHost: $host\r\n\r\n"
            }
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()
            setTtl(socket, 64)
        } catch (_: Exception) {}
        sendSplit(out, data, splitAt)
    }

    private fun getSplitPosition(data: ByteArray, pos: SplitPos): Int = when (pos) {
        SplitPos.POS_1 -> 1
        SplitPos.POS_2 -> 2
        SplitPos.MIDSLD -> FakePackets.findSniMidSld(data)
        SplitPos.SNIEXT_PLUS_1 -> {
            val off = FakePackets.findSniOffset(data)
            if (off > 0) off + 1 else data.size / 2
        }
    }

    private fun getFakeTcpData(): ByteArray = when (strategy.fakeTlsMod) {
        FakeTlsMod.RND -> FakePackets.buildTlsClientHello(strategy.fakeTlsSni, randomize = true)
        FakeTlsMod.DUPSID -> FakePackets.buildTlsClientHello(strategy.fakeTlsSni, dupSid = true)
        FakeTlsMod.NONE -> when (strategy.fakeData) {
            FakeData.QUIC_GOOGLE -> FakePackets.fromAsset("quic_initial_www_google_com.bin", FakePackets.QUIC_INITIAL_GOOGLE)
            FakeData.QUIC_DBANK -> FakePackets.fromAsset("quic_initial_www_google_com.bin", FakePackets.QUIC_INITIAL_DBANK)
            FakeData.QUIC_4PDA -> FakePackets.fromAsset("quic_initial_4pda_to.bin", FakePackets.UNKNOWN_UDP)
            FakeData.QUIC_5KA -> FakePackets.fromAsset("quic_initial_5ka_ru.bin", FakePackets.UNKNOWN_UDP)
            FakeData.QUIC_LEMANA -> FakePackets.fromAsset("quic_initial_lemana_pro.bin", FakePackets.UNKNOWN_UDP)
            FakeData.QUIC_RUTUBE -> FakePackets.fromAsset("quic_initial_rutube_ru.bin", FakePackets.UNKNOWN_UDP)
            FakeData.QUIC_STEAM_CHAT -> FakePackets.fromAsset("quic_initial_steam_chat_com.bin", FakePackets.UNKNOWN_UDP)
            FakeData.QUIC_STEAMCOMMUNITY -> FakePackets.fromAsset("quic_initial_steamcommunity_com.bin", FakePackets.UNKNOWN_UDP)
            FakeData.QUIC_STEAMSTATIC -> FakePackets.fromAsset("quic_initial_steamstatic_com.bin", FakePackets.UNKNOWN_UDP)
            FakeData.QUIC_TENCENT -> FakePackets.fromAsset("quic_initial_tencent_com.bin", FakePackets.UNKNOWN_UDP)
            FakeData.TLS_GOOGLE -> FakePackets.fromAsset("tls_clienthello_www_google_com.bin", FakePackets.TLS_CLIENT_HELLO_GOOGLE)
            FakeData.TLS_MAX -> FakePackets.fromAsset("tls_clienthello_max_ru.bin", FakePackets.TLS_CLIENT_HELLO_MAX)
            FakeData.TLS_4PDA -> FakePackets.fromAsset("tls_clienthello_4pda_to.bin", FakePackets.TLS_CLIENT_HELLO_4PDA)
            FakeData.TLS_5KA -> FakePackets.fromAsset("tls_clienthello_5ka_ru.bin", FakePackets.TLS_CLIENT_HELLO_4PDA)
            FakeData.TLS_AWSWAF -> FakePackets.fromAsset("tls_clienthello_awswaf_com.bin", FakePackets.TLS_CLIENT_HELLO_GOOGLE)
            FakeData.TLS_SOCHI -> FakePackets.fromAsset("tls_clienthello_sochi_park.bin", FakePackets.TLS_CLIENT_HELLO_GOOGLE)
            FakeData.TLS_STEAM_CHAT -> FakePackets.fromAsset("tls_clienthello_steam_chat_com.bin", FakePackets.TLS_CLIENT_HELLO_GOOGLE)
            FakeData.TLS_STEAMSTATIC -> FakePackets.fromAsset("tls_clienthello_steamstatic_com.bin", FakePackets.TLS_CLIENT_HELLO_GOOGLE)
            FakeData.TLS_TEAMSPEAK -> FakePackets.fromAsset("tls_clienthello_teamspeak_com.bin", FakePackets.TLS_CLIENT_HELLO_GOOGLE)
            FakeData.TLS_SFERUM -> FakePackets.fromAsset("tls_clienthello_www_sferum_ru.bin", FakePackets.TLS_CLIENT_HELLO_GOOGLE)
            FakeData.STUN -> FakePackets.fromAsset("stun.bin", FakePackets.STUN_BINDING)
            FakeData.STUN2 -> FakePackets.fromAsset("stun2.bin", FakePackets.STUN_BINDING)
            FakeData.ACTIVE_DISCORD_UDP -> FakePackets.fromAsset("ACTIVE_DISCORD_UDP.bin", FakePackets.QUIC_INITIAL_DBANK)
            FakeData.ACTIVE_GAME_UDP -> FakePackets.fromAsset("ACTIVE_GAME_UDP.bin", FakePackets.UNKNOWN_UDP)
            FakeData.UNKNOWN_UDP -> FakePackets.UNKNOWN_UDP
        }
    }

    private fun getFakeUdpData(payload: ByteArray): ByteArray {
        return when (DomainLists.classifyUdp(payload)) {
            DomainLists.UdpType.QUIC -> when (strategy.fakeData) {
                FakeData.ACTIVE_DISCORD_UDP -> FakePackets.fromAsset("ACTIVE_DISCORD_UDP.bin", FakePackets.QUIC_INITIAL_DBANK)
                else -> FakePackets.fromAsset("quic_initial_www_google_com.bin", FakePackets.QUIC_INITIAL_GOOGLE)
            }
            DomainLists.UdpType.STUN -> FakePackets.fromAsset("ACTIVE_DISCORD_UDP.bin", FakePackets.STUN_BINDING)
            DomainLists.UdpType.DISCORD -> FakePackets.fromAsset("ACTIVE_DISCORD_UDP.bin", FakePackets.QUIC_INITIAL_DBANK)
            DomainLists.UdpType.UNKNOWN -> when (strategy.fakeData) {
                FakeData.ACTIVE_GAME_UDP -> FakePackets.fromAsset("ACTIVE_GAME_UDP.bin", FakePackets.UNKNOWN_UDP)
                else -> FakePackets.UNKNOWN_UDP
            }
        }
    }
}
