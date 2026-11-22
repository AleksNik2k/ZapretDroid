package com.zapret.droid.proxy

import com.zapret.droid.strategies.DesyncMethod
import com.zapret.droid.strategies.SplitPos
import com.zapret.droid.strategies.Strategy
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

class DpiBypass(private val strategy: Strategy) {
    fun applyOnConnect(socket: Socket, clientData: ByteArray, out: OutputStream) {
        socket.tcpNoDelay = true
        val splitRequested = strategy.dpiDesync.any {
            it == DesyncMethod.MULTISPLIT ||
                it == DesyncMethod.FAKEDSPLIT ||
                it == DesyncMethod.MULTIDISORDER ||
                it == DesyncMethod.HOSTFAKESPLIT ||
                it == DesyncMethod.SYNDATA
        }
        if (splitRequested && clientData.size > 1) {
            val splitAt = getSplitPosition(clientData, strategy.splitPos)
                .coerceIn(1, clientData.size - 1)
            out.write(clientData, 0, splitAt)
            out.flush()
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            out.write(clientData, splitAt, clientData.size - splitAt)
        } else {
            out.write(clientData)
        }
        out.flush()
    }

    fun applyOnUdpSend(socket: DatagramSocket, realDst: InetSocketAddress, payload: ByteArray) {
        socket.send(DatagramPacket(payload, payload.size, realDst))
    }

    private fun getSplitPosition(data: ByteArray, position: SplitPos): Int = when (position) {
        SplitPos.POS_1 -> 1
        SplitPos.POS_2 -> 2
        SplitPos.MIDSLD -> FakePackets.findSniMidSld(data)
        SplitPos.SNIEXT_PLUS_1 -> {
            val offset = FakePackets.findSniOffset(data)
            if (offset > 0) offset + 1 else data.size / 2
        }
    }
}
