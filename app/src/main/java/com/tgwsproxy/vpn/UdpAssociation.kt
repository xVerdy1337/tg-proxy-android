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
 * that pushes replies back into the TUN, and an idle timeout reaped by the service. The socket is
 * pinned to its single peer, because everything it receives is handed to the app as if that peer
 * had sent it.
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

    /**
     * The only peer this association may exchange datagrams with. Resolved once from the raw 4-byte
     * destination, so `getByAddress` is a pure parse that never touches DNS. Nullable purely so an
     * address of impossible length fails the association in [start] instead of throwing out of the
     * constructor, where the service has no association object to clean up.
     */
    private val peer: InetSocketAddress? = try {
        InetSocketAddress(InetAddress.getByAddress(serverIp), serverPort)
    } catch (_: Exception) {
        null
    }

    @Volatile var lastUsed = System.currentTimeMillis(); private set
    @Volatile private var closed = false

    fun start(): Boolean {
        val dst = peer
        if (dst == null) { close(); return false }
        // protect() has to land before the socket carries anything: connect() below already does the
        // route lookup and picks a source address, so an unprotected socket would be pinned to the
        // TUN and loop its own traffic back into us.
        if (!tunnel.protectUdp(socket)) { close(); return false }
        // Pin the socket to that one peer so the OS drops datagrams from every other source. Without
        // it the reader accepts whatever lands on this ephemeral port and rebuilds it below as if it
        // came from serverIp:serverPort — on a DNS flow that hands an off-path attacker who merely
        // guesses the port a forged answer injected straight into the app's resolver.
        try { socket.connect(dst) } catch (_: Exception) { close(); return false }
        val dstAddr: InetAddress = dst.address
        tunnel.relayExecutor.execute {
            val buf = ByteArray(65535)
            try {
                while (!closed) {
                    val dp = DatagramPacket(buf, buf.size)
                    socket.receive(dp)
                    // connect() is the actual guarantee — the kernel refuses datagrams from anyone
                    // but the peer. This is only belt-and-braces for stacks where the source is
                    // observable, so it must fail open: libcore skips source extraction once the
                    // impl is natively connected, and where nothing backfills the fields they stay
                    // at DatagramPacket's null/-1 defaults. Comparing those would drop every reply
                    // and kill DNS for every app with nothing in the log. Judge only what the
                    // platform actually reported; dropping before lastUsed is touched also stops a
                    // stray datagram from holding a dead association open past its idle timeout.
                    val src: InetAddress? = dp.address
                    if (src != null && dp.port > 0 && (src != dstAddr || dp.port != serverPort)) continue
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
        val dst = peer ?: return
        lastUsed = System.currentTimeMillis()
        try {
            // Same address the socket is connected to, so the explicit destination is redundant —
            // it is kept because a connected socket throws on any *other* destination, which is
            // exactly the invariant we want enforced at the send site rather than assumed.
            socket.send(DatagramPacket(payload, payload.size, dst))
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
