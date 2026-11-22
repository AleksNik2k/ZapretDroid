package com.zapret.droid.proxy

import android.net.VpnService
import com.zapret.droid.strategies.Strategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class TcpProxyHandler(
    private val vpnService: VpnService,
    private val strategy: Strategy,
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeConnections = ConcurrentHashMap<ConnectionKey, TcpSession>()
    private val connecting: MutableSet<ConnectionKey> =
        Collections.newSetFromMap(ConcurrentHashMap<ConnectionKey, Boolean>())

    private data class TcpSegment(
        val seq: Long,
        val ack: Long,
        val flags: Int,
        val window: Int,
        val payload: ByteArray,
        val writeTun: (ByteArray) -> Unit
    )

    private data class PendingSegment(
        val seq: Long,
        val payload: ByteArray,
        val flags: Int,
        var sentAt: Long,
        var retries: Int = 0
    ) {
        val endSeq: Long get() =
            (seq + payload.size + if (flags and PacketUtils.FLAG_FIN != 0) 1 else 0) and SEQ_MASK
    }

    private class TcpSession(
        val key: ConnectionKey,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val clientSeq: Long,
        val serverSeq: Long,
        val socket: Socket,
        val writeTun: (ByteArray) -> Unit
    ) {
        val inbound = Channel<TcpSegment>(TCP_INBOUND_QUEUE_SIZE)
        val stateLock = Mutex()
        val pending = ArrayDeque<PendingSegment>()
        val initialData = ByteArrayOutputStream()
        @Volatile var nextClientSeq = clientSeq
        @Volatile var nextServerSeq = serverSeq
        @Volatile var receiveWindow = 65535
        var initialDataHandled = false
        @Volatile var pendingBytes = 0
        @Volatile var closed = false
        @Volatile var serverFinSent = false
    }

    fun handlePacket(buf: ByteArray, writeTun: (ByteArray) -> Unit) {
        if (!PacketUtils.isValidIpPacket(buf) || PacketUtils.isFragment(buf)) return
        val ipHeaderLength = PacketUtils.parseIpHeaderLen(buf)
        val totalLength = PacketUtils.parseIpTotalLength(buf)
        if (ipHeaderLength + 20 > totalLength) return
        val tcpHeaderLength = PacketUtils.parseTcpHeaderLen(buf, ipHeaderLength)
        if (tcpHeaderLength < 20 || ipHeaderLength + tcpHeaderLength > totalLength) return

        val srcIp = PacketUtils.parseIpSrc(buf)
        val dstIp = PacketUtils.parseIpDst(buf)
        val srcPort = PacketUtils.parseTcpSrcPort(buf, ipHeaderLength)
        val dstPort = PacketUtils.parseTcpDstPort(buf, ipHeaderLength)
        val seq = PacketUtils.parseTcpSeq(buf, ipHeaderLength)
        val ack = PacketUtils.parseTcpAck(buf, ipHeaderLength)
        val flags = PacketUtils.parseTcpFlags(buf, ipHeaderLength)
        val window = ((buf[ipHeaderLength + 14].toInt() and 0xFF) shl 8) or
            (buf[ipHeaderLength + 15].toInt() and 0xFF)
        val payload = PacketUtils.parseTcpPayload(buf, ipHeaderLength)
        val key = ConnectionKey(
            PacketUtils.bytesToIp(srcIp), srcPort,
            PacketUtils.bytesToIp(dstIp), dstPort, 6
        )

        if (flags and PacketUtils.FLAG_RST != 0) {
            activeConnections[key]?.let { closeSession(it) }
            return
        }
        if (flags and PacketUtils.FLAG_SYN != 0 && flags and PacketUtils.FLAG_ACK == 0) {
            val existing = activeConnections[key]
            if (existing != null) {
                sendSynAck(existing, writeTun)
            } else if (connecting.add(key)) {
                scope.launch { connect(key, srcIp, dstIp, srcPort, dstPort, seq, writeTun) }
            }
            return
        }

        activeConnections[key]?.let { session ->
            val result = session.inbound.trySend(
                TcpSegment(seq, ack, flags, window, payload, writeTun)
            )
            if (result.isFailure) sendAck(session, writeTun)
        }
    }

    private suspend fun connect(
        key: ConnectionKey,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        clientIsn: Long,
        writeTun: (ByteArray) -> Unit
    ) {
        var socket: Socket? = null
        try {
            socket = Socket()
            if (!vpnService.protect(socket)) {
                throw IllegalStateException("Unable to protect upstream TCP socket from VPN routing")
            }
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(key.dstIp, key.dstPort), 10_000)

            val serverIsn = (System.nanoTime() and SEQ_MASK)
            val session = TcpSession(
                key = key,
                srcIp = srcIp,
                dstIp = dstIp,
                srcPort = srcPort,
                dstPort = dstPort,
                clientSeq = (clientIsn + 1) and SEQ_MASK,
                serverSeq = (serverIsn + 1) and SEQ_MASK,
                socket = socket,
                writeTun = writeTun
            )
            activeConnections[key] = session
            sendSynAck(session, writeTun, serverIsn)
            scope.launch { processClientSegments(session) }
            scope.launch { forwardServerData(session) }
            scope.launch { retransmitUnacknowledged(session) }
            socket = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            onLog("Connect failed to ${key.dstIp}:${key.dstPort} - ${e.message}")
            val reset = PacketUtils.buildIpTcpPacket(
                srcIp = dstIp, dstIp = srcIp,
                srcPort = dstPort, dstPort = srcPort,
                seq = 0, ack = (clientIsn + 1) and SEQ_MASK,
                flags = PacketUtils.FLAG_RST or PacketUtils.FLAG_ACK
            )
            writeTun(reset)
        } finally {
            connecting.remove(key)
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun sendSynAck(
        session: TcpSession,
        writeTun: (ByteArray) -> Unit,
        seq: Long = (session.nextServerSeq - 1) and SEQ_MASK
    ) {
        writeTun(PacketUtils.buildIpTcpPacket(
            srcIp = session.dstIp, dstIp = session.srcIp,
            srcPort = session.dstPort, dstPort = session.srcPort,
            seq = seq,
            ack = session.nextClientSeq,
            flags = PacketUtils.FLAG_SYN or PacketUtils.FLAG_ACK,
            windowSize = 65535
        ))
    }

    private suspend fun processClientSegments(session: TcpSession) {
        for (segment in session.inbound) {
            if (session.closed) break
            session.stateLock.withLock {
                if (segment.flags and PacketUtils.FLAG_ACK != 0) {
                    acknowledgeServerData(session, segment.ack, segment.window)
                    if (session.serverFinSent && session.pending.isEmpty()) {
                        closeSession(session)
                        return@withLock
                    }
                }
                if (segment.payload.isNotEmpty() || segment.flags and PacketUtils.FLAG_FIN != 0) {
                    if (segment.seq != session.nextClientSeq) {
                        sendAck(session, segment.writeTun)
                        return@withLock
                    }
                    try {
                        if (segment.payload.isNotEmpty()) {
                            processClientPayload(session, segment.payload)
                            session.nextClientSeq =
                                (session.nextClientSeq + segment.payload.size) and SEQ_MASK
                        }
                        if (segment.flags and PacketUtils.FLAG_FIN != 0) {
                            session.nextClientSeq = (session.nextClientSeq + 1) and SEQ_MASK
                            try { session.socket.shutdownOutput() } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        onLog("TCP data error ${session.key.dstIp}:${session.dstPort} - ${e.message}")
                        closeSession(session)
                        return@withLock
                    }
                }
                sendAck(session, segment.writeTun)
            }
        }
    }

    private fun processClientPayload(session: TcpSession, payload: ByteArray) {
        val output = session.socket.getOutputStream()
        if (session.initialDataHandled) {
            output.write(payload)
            output.flush()
            return
        }

        session.initialData.write(payload)
        val pending = session.initialData.toByteArray()
        val initialLength = completeInitialMessageLength(pending)
        if (initialLength == null && pending.size < MAX_INITIAL_BUFFER) return

        val initial = if (initialLength == null) pending else pending.copyOf(initialLength)
        val rest = if (initial.size < pending.size) pending.copyOfRange(initial.size, pending.size) else ByteArray(0)
        session.initialData.reset()
        session.initialDataHandled = true

        if (DomainLists.shouldBypassTcp(session.key.dstIp, initial) &&
            session.dstPort in strategy.tcpPorts
        ) {
            DpiBypass(strategy).applyOnConnect(session.socket, initial, output)
        } else {
            output.write(initial)
        }
        if (rest.isNotEmpty()) output.write(rest)
        output.flush()
    }

    private fun completeInitialMessageLength(data: ByteArray): Int? {
        if (data.isEmpty()) return 0
        val first = data[0].toInt() and 0xFF
        if (first == TLS_HANDSHAKE_RECORD) {
            if (data.size < 5) return null
            val recordLength = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
            if (recordLength > MAX_TLS_RECORD_SIZE) return data.size
            val expected = 5 + recordLength
            return if (data.size >= expected) expected else null
        }
        if (looksLikeHttp(data)) {
            val end = findHeaderEnd(data)
            return when {
                end >= 0 -> end
                data.size >= MAX_HTTP_HEADER_SIZE -> data.size
                else -> null
            }
        }
        return data.size
    }

    private fun looksLikeHttp(data: ByteArray): Boolean {
        val prefix = String(data, 0, min(data.size, HTTP_PREFIX.length), Charsets.US_ASCII)
        return HTTP_METHODS.any { it.startsWith(prefix) || prefix.startsWith(it) }
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        for (i in 0 until data.size - 3) {
            if (data[i] == 13.toByte() && data[i + 1] == 10.toByte() &&
                data[i + 2] == 13.toByte() && data[i + 3] == 10.toByte()
            ) return i + 4
        }
        return -1
    }

    private suspend fun forwardServerData(session: TcpSession) {
        val input: InputStream = session.socket.getInputStream()
        val buffer = ByteArray(16 * 1024)
        try {
            while (!session.closed) {
                if (awaitSendWindow(session) <= 0) break
                val count = input.read(buffer)
                if (count < 0) break
                var offset = 0
                while (offset < count && !session.closed) {
                    val available = awaitSendWindow(session)
                    if (available <= 0) break
                    val chunkLength = min(MAX_TCP_PAYLOAD, min(count - offset, available))
                    val payload = buffer.copyOfRange(offset, offset + chunkLength)
                    val packet = session.stateLock.withLock {
                        val seq = session.nextServerSeq
                        session.nextServerSeq = (seq + payload.size) and SEQ_MASK
                        session.pending.addLast(
                            PendingSegment(
                                seq, payload, PacketUtils.FLAG_PSH or PacketUtils.FLAG_ACK,
                                System.currentTimeMillis()
                            )
                        )
                        session.pendingBytes += payload.size
                        PacketUtils.buildIpTcpPacket(
                            srcIp = session.dstIp, dstIp = session.srcIp,
                            srcPort = session.dstPort, dstPort = session.srcPort,
                            seq = seq,
                            ack = session.nextClientSeq,
                            flags = PacketUtils.FLAG_PSH or PacketUtils.FLAG_ACK,
                            payload = payload
                        )
                    }
                    session.writeTun(packet)
                    offset += chunkLength
                }
            }
        } catch (e: Exception) {
            if (!session.closed && e !is CancellationException) {
                onLog("TCP receive error ${session.key.dstIp}:${session.dstPort} - ${e.message}")
            }
        }

        if (!session.closed) {
            val finPacket = session.stateLock.withLock {
                if (session.closed || session.serverFinSent) return@withLock null
                val fin = PacketUtils.buildIpTcpPacket(
                    srcIp = session.dstIp, dstIp = session.srcIp,
                    srcPort = session.dstPort, dstPort = session.srcPort,
                    seq = session.nextServerSeq,
                    ack = session.nextClientSeq,
                    flags = PacketUtils.FLAG_FIN or PacketUtils.FLAG_ACK
                )
                session.pending.addLast(
                    PendingSegment(
                        session.nextServerSeq, ByteArray(0),
                        PacketUtils.FLAG_FIN or PacketUtils.FLAG_ACK,
                        System.currentTimeMillis()
                    )
                )
                session.nextServerSeq = (session.nextServerSeq + 1) and SEQ_MASK
                session.serverFinSent = true
                fin
            }
            finPacket?.let(session.writeTun)
        }
    }

    private suspend fun awaitSendWindow(session: TcpSession): Int {
        while (!session.closed) {
            val available = session.stateLock.withLock {
                session.receiveWindow - session.pendingBytes
            }
            if (available > 0) return available
            delay(10)
        }
        return 0
    }

    private suspend fun retransmitUnacknowledged(session: TcpSession) {
        while (!session.closed) {
            delay(RETRANSMIT_INTERVAL_MS)
            val due = session.stateLock.withLock {
                val now = System.currentTimeMillis()
                session.pending.mapNotNull { segment ->
                    if (now - segment.sentAt < RETRANSMIT_INTERVAL_MS) return@mapNotNull null
                    if (segment.retries >= MAX_RETRANSMITS) return@mapNotNull null
                    segment.retries++
                    segment.sentAt = now
                    PacketUtils.buildIpTcpPacket(
                        srcIp = session.dstIp, dstIp = session.srcIp,
                        srcPort = session.dstPort, dstPort = session.srcPort,
                        seq = segment.seq,
                        ack = session.nextClientSeq,
                        flags = segment.flags,
                        payload = segment.payload
                    )
                }
            }
            val exhausted = session.stateLock.withLock {
                session.pending.any { it.retries >= MAX_RETRANSMITS &&
                    System.currentTimeMillis() - it.sentAt >= RETRANSMIT_INTERVAL_MS }
            }
            if (exhausted) {
                onLog("TCP peer stopped acknowledging ${session.key.dstIp}:${session.dstPort}")
                closeSession(session)
                return
            }
            due.forEach(session.writeTun)
        }
    }

    private fun acknowledgeServerData(session: TcpSession, ack: Long, window: Int) {
        session.receiveWindow = window
        while (session.pending.isNotEmpty()) {
            val first = session.pending.first()
            if (!seqAtOrAfter(ack, first.endSeq)) break
            session.pending.removeFirst()
            session.pendingBytes -= first.payload.size
        }
    }

    private fun sendAck(session: TcpSession, writeTun: (ByteArray) -> Unit) {
        writeTun(PacketUtils.buildIpTcpPacket(
            srcIp = session.dstIp, dstIp = session.srcIp,
            srcPort = session.dstPort, dstPort = session.srcPort,
            seq = session.nextServerSeq,
            ack = session.nextClientSeq,
            flags = PacketUtils.FLAG_ACK
        ))
    }

    private fun seqAtOrAfter(value: Long, reference: Long): Boolean =
        ((value - reference) and SEQ_MASK) < HALF_SEQ_SPACE

    private fun closeSession(session: TcpSession) {
        if (session.closed) return
        session.closed = true
        activeConnections.remove(session.key, session)
        session.inbound.close()
        try { session.socket.close() } catch (_: Exception) {}
    }

    fun shutdown() {
        scope.cancel()
        activeConnections.values.toList().forEach(::closeSession)
        activeConnections.clear()
        connecting.clear()
    }

    companion object {
        private const val SEQ_MASK = 0xFFFFFFFFL
        private const val HALF_SEQ_SPACE = 0x80000000L
        private const val MAX_TCP_PAYLOAD = 536
        private const val MAX_INITIAL_BUFFER = 32 * 1024
        private const val MAX_HTTP_HEADER_SIZE = 16 * 1024
        private const val MAX_TLS_RECORD_SIZE = 18 * 1024
        private const val RETRANSMIT_INTERVAL_MS = 500L
        private const val MAX_RETRANSMITS = 5
        private const val TCP_INBOUND_QUEUE_SIZE = 128
        private const val TLS_HANDSHAKE_RECORD = 0x16
        private const val HTTP_PREFIX = "GET "
        private val HTTP_METHODS = listOf(
            "GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT "
        )
    }
}
