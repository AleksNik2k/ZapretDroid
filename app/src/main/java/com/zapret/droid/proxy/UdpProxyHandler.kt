package com.zapret.droid.proxy

import android.net.VpnService
import com.zapret.droid.strategies.Strategy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.*
import java.util.concurrent.ConcurrentHashMap

class UdpProxyHandler(
    private val vpnService: VpnService,
    private val strategy: Strategy,
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class UdpSession(
        val key: ConnectionKey,
        val socket: DatagramSocket,
        val outbound: Channel<ByteArray>,
        @Volatile
        var lastActivity: Long = System.currentTimeMillis(),
        val sendJob: Job? = null,
        val receiveJob: Job? = null
    )

    private val sessions = ConcurrentHashMap<ConnectionKey, UdpSession>()
    private val SESSION_TIMEOUT_MS = 30_000L

    fun handlePacket(buf: ByteArray, writeTun: (ByteArray) -> Unit) {
        if (!PacketUtils.isValidIpPacket(buf) || PacketUtils.isFragment(buf)) return
        val ipHL = PacketUtils.parseIpHeaderLen(buf)
        val totalLength = PacketUtils.parseIpTotalLength(buf)
        if (ipHL + 8 > totalLength) return
        val udpLength = PacketUtils.parseUdpLength(buf, ipHL)
        if (udpLength < 8 || ipHL + udpLength > totalLength) return
        val srcIp = PacketUtils.parseIpSrc(buf)
        val dstIp = PacketUtils.parseIpDst(buf)
        val srcPort = PacketUtils.parseUdpSrcPort(buf, ipHL)
        val dstPort = PacketUtils.parseUdpDstPort(buf, ipHL)
        val payload = PacketUtils.parseUdpPayload(buf, ipHL)

        val key = ConnectionKey(
            PacketUtils.bytesToIp(srcIp), srcPort,
            PacketUtils.bytesToIp(dstIp), dstPort,
            17
        )

        val session = try {
            sessions[key] ?: run {
                val created = createSession(key, srcIp, dstIp, srcPort, dstPort, writeTun)
                val existing = sessions.putIfAbsent(key, created)
                if (existing == null) created else {
                    disposeSession(created)
                    existing
                }
            }
        } catch (e: Exception) {
            onLog("UDP session failed ${key.dstIp}:${key.dstPort} - ${e.message}")
            return
        }
        session.lastActivity = System.currentTimeMillis()

        if (session.outbound.trySend(payload).isFailure) {
            onLog("UDP packet dropped because the session queue is full ${key.dstIp}:${key.dstPort}")
        }
    }

    private fun createSession(
        key: ConnectionKey,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        writeTun: (ByteArray) -> Unit
    ): UdpSession {
        val sock = DatagramSocket()
        try {
            if (!vpnService.protect(sock)) {
                throw IllegalStateException("Unable to protect upstream UDP socket from VPN routing")
            }
            sock.connect(InetSocketAddress(key.dstIp, key.dstPort))
        } catch (e: Exception) {
            sock.close()
            throw e
        }

        val outbound = Channel<ByteArray>(UDP_OUTBOUND_QUEUE_SIZE)
        val sendJob = scope.launch {
            try {
                for (payload in outbound) {
                    if (DomainLists.shouldBypassUdp(key.dstIp, key.dstPort, payload)) {
                        DpiBypass(strategy).applyOnUdpSend(
                            sock,
                            InetSocketAddress(key.dstIp, key.dstPort),
                            payload
                        )
                    } else {
                        sock.send(DatagramPacket(payload, payload.size))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) {
                    onLog("UDP send error ${key.dstIp}:${key.dstPort} - ${e.message}")
                    closeSession(key)
                }
            }
        }
        val receiveJob = scope.launch {
            val buf = ByteArray(65507)
            while (isActive) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val data = pkt.data.copyOf(pkt.length)
                    val respPkt = PacketUtils.buildIpUdpPacket(
                        srcIp = dstIp, dstIp = srcIp,
                        srcPort = dstPort, dstPort = srcPort,
                        payload = data
                    )
                    PacketUtils.fragmentIpPacket(respPkt, PacketUtils.DEFAULT_MTU).forEach(writeTun)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isActive) {
                        onLog("UDP receive error ${key.dstIp}:${key.dstPort} - ${e.message}")
                        closeSession(key)
                    }
                    break
                }
            }
        }

        return UdpSession(key, sock, outbound, sendJob = sendJob, receiveJob = receiveJob)
    }

    private fun closeSession(key: ConnectionKey) {
        val session = sessions.remove(key) ?: return
        disposeSession(session)
    }

    private fun disposeSession(session: UdpSession) {
        session.outbound.close()
        session.sendJob?.cancel()
        session.receiveJob?.cancel()
        try { session.socket.close() } catch (_: Exception) {}
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        val expiredKeys = sessions.entries
            .filter { (_, session) -> now - session.lastActivity > SESSION_TIMEOUT_MS }
            .map { (key, _) -> key }

        expiredKeys.forEach { key ->
            closeSession(key)
        }
    }

    fun shutdown() {
        scope.cancel()
        sessions.values.forEach {
            it.outbound.close()
            it.sendJob?.cancel()
            it.receiveJob?.cancel()
            try { it.socket.close() } catch (_: Exception) {}
        }
        sessions.clear()
    }

    private companion object {
        const val UDP_OUTBOUND_QUEUE_SIZE = 32
    }
}
