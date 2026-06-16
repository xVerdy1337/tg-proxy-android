package com.tgwsproxy.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * One UDP flow (mostly DNS) relayed over a protected [DatagramSocket]. QUIC (UDP:443) is never
 * routed here — the service drops it so apps fall back to TCP/TLS where the desync applies.
 *
 * Kept intentionally simple: one protected socket per (srcPort,dst,dstPort) tuple, a reader thread
 * that pushes replies back into the TUN, and an idle timeout reaped by the service.
 */
class UdpAssociation(
    private val clientIp: ByteArray,
    private val clientPort: Int,
    private val serverIp: ByteArray,
    private val serverPort: Int,
    private val tunnel: Tunnel,
    private val key: Long,
) {
    private val socket = DatagramSocket()
    @Volatile var lastUsed = System.currentTimeMillis(); private set
    @Volatile private var closed = false

    fun start(): Boolean {
        if (!tunnel.protectUdp(socket)) { close(); return false }
        tunnel.relayExecutor.execute {
            val buf = ByteArray(65535)
            try {
                while (!closed) {
                    val dp = DatagramPacket(buf, buf.size)
                    socket.receive(dp)
                    lastUsed = System.currentTimeMillis()
                    val data = dp.data.copyOf(dp.length)
                    val pkt = PacketUtils.buildUdp(serverIp, serverPort, clientIp, clientPort, data)
                    tunnel.writeToTun(pkt)
                }
            } catch (_: Exception) {
                close()
            }
        }
        return true
    }

    fun onClientPayload(payload: ByteArray) {
        if (closed) return
        lastUsed = System.currentTimeMillis()
        try {
            val addr: InetAddress = InetAddress.getByAddress(serverIp)
            socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(addr, serverPort)))
        } catch (_: Exception) {
            close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { socket.close() } catch (_: Exception) {}
        tunnel.onConnectionClosed(key, udp = true)
    }
}
