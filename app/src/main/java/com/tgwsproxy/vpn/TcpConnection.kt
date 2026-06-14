package com.tgwsproxy.vpn

import com.tgwsproxy.desync.DesyncEngine
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * One TCP flow from a captured app, terminated locally and relayed to the real destination over a
 * VPN-protected socket. On the very first client→server payload (the TLS ClientHello) we run
 * [DesyncEngine] so the provider's DPI can't read the SNI — that's what unblocks YouTube/Instagram.
 *
 * This is a deliberately compact, "good-enough" TCP: it ACKs in-order data, ignores out-of-order
 * segments (the client retransmits), and relies on the local TUN write being reliable to the
 * kernel (so we don't implement our own retransmission for the downstream direction). It is NOT a
 * full RFC 793 stack — it's the standard lightweight tun2socks-style approach, tuned on-device.
 */
class TcpConnection(
    private val clientIp: ByteArray,
    private val clientPort: Int,
    private val serverIp: ByteArray,
    private val serverPort: Int,
    private val tunnel: Tunnel,
    private val key: Long,
    private val method: DesyncEngine.Method?,   // null = no desync (plain relay)
) {
    private enum class State { SYN_RECEIVED, ESTABLISHED, CLOSING, CLOSED }

    @Volatile private var state = State.SYN_RECEIVED
    private val lock = Any()

    private var rcvNxt = 0L            // next client seq we expect
    private var sndNxt = 0L            // our next seq to send to client
    private var clientWindow = 65535

    private var upstream: Socket? = null
    private var upOut: OutputStream? = null
    @Volatile private var connected = false
    @Volatile private var firstPayloadSent = false
    private val pendingToUpstream = ArrayList<ByteArray>()

    companion object {
        private const val MSS = 1400
        private const val WIN = 65535
    }

    /** Called by the service when the initial SYN for this flow arrives. */
    fun onSyn(clientSeq: Long, window: Int) {
        synchronized(lock) {
            rcvNxt = (clientSeq + 1) and 0xFFFFFFFFL
            sndNxt = (Random.nextLong() and 0xFFFFFFFFL)
            clientWindow = window
            // SYN+ACK — advertise our MSS so the app caps its segments at a size we (and the
            // downstream path) handle cleanly. Without this the app assumes 1460 and can emit
            // segments that don't round-trip well through the userspace stack.
            sendSegment(PacketUtils.TcpFlag.SYN or PacketUtils.TcpFlag.ACK, ByteArray(0), mss = MSS)
            sndNxt = (sndNxt + 1) and 0xFFFFFFFFL
        }
        connectUpstream()
    }

    private fun connectUpstream() {
        thread(name = "up-tcp-$serverPort", isDaemon = true) {
            try {
                val s = Socket()
                if (!tunnel.protectTcp(s)) { reset(); return@thread }
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(PacketUtils.ipToString(serverIp), serverPort), 10_000)
                synchronized(lock) {
                    upstream = s
                    upOut = s.getOutputStream()
                    connected = true
                    // flush anything the client already sent before connect completed
                    flushPendingLocked()
                }
                pumpDownstream(s.getInputStream())
            } catch (_: Exception) {
                reset()
            }
        }
    }

    /** Called by the service for every non-SYN TCP packet belonging to this flow. */
    fun onPacket(seq: Long, ack: Long, flags: Int, window: Int, payload: ByteArray) {
        synchronized(lock) {
            if (state == State.CLOSED) return
            clientWindow = window

            if (flags and PacketUtils.TcpFlag.RST != 0) { closeLocked(sendRst = false); return }
            if (flags and PacketUtils.TcpFlag.SYN != 0) {
                // SYN retransmit before our SYN+ACK was seen — resend it.
                val saved = sndNxt
                sndNxt = (sndNxt - 1) and 0xFFFFFFFFL
                sendSegment(PacketUtils.TcpFlag.SYN or PacketUtils.TcpFlag.ACK, ByteArray(0))
                sndNxt = saved
                return
            }
            if (state == State.SYN_RECEIVED) state = State.ESTABLISHED

            if (payload.isNotEmpty()) {
                when {
                    seq == rcvNxt -> {
                        rcvNxt = (rcvNxt + payload.size) and 0xFFFFFFFFL
                        queueToUpstreamLocked(payload)
                        sendSegment(PacketUtils.TcpFlag.ACK, ByteArray(0))
                    }
                    else -> {
                        // out-of-order or already-seen → dup-ACK so the client retransmits.
                        sendSegment(PacketUtils.TcpFlag.ACK, ByteArray(0))
                    }
                }
            }

            if (flags and PacketUtils.TcpFlag.FIN != 0) {
                // Only honor FIN that lines up with our expected seq.
                if (seq + payload.size == rcvNxt || payload.isEmpty()) {
                    rcvNxt = (rcvNxt + 1) and 0xFFFFFFFFL
                    sendSegment(PacketUtils.TcpFlag.ACK, ByteArray(0))
                    try { upstream?.shutdownOutput() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun queueToUpstreamLocked(data: ByteArray) {
        if (!connected) { pendingToUpstream.add(data); return }
        writeUpstreamLocked(data)
    }

    private fun flushPendingLocked() {
        val copy = ArrayList(pendingToUpstream)
        pendingToUpstream.clear()
        for (chunk in copy) writeUpstreamLocked(chunk)
    }

    private fun writeUpstreamLocked(data: ByteArray) {
        val out = upOut ?: return
        try {
            if (!firstPayloadSent && method != null && DesyncEngine.isClientHello(data)) {
                firstPayloadSent = true
                val safeMethod = when (method) {
                    DesyncEngine.Method.DISORDER -> DesyncEngine.Method.SPLIT // raw reorder needs IP-level control
                    else -> method
                }
                val plan = DesyncEngine.plan(data, safeMethod)
                for (chunk in plan.chunks) { out.write(chunk); out.flush() }
            } else {
                firstPayloadSent = true
                out.write(data); out.flush()
            }
        } catch (_: Exception) { reset() }
    }

    /** Reads from the real server and streams it back to the client as TCP segments. */
    private fun pumpDownstream(inp: InputStream) {
        val buf = ByteArray(MSS)
        try {
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val chunk = buf.copyOf(n)
                synchronized(lock) {
                    if (state == State.CLOSED) return
                    sendSegment(PacketUtils.TcpFlag.ACK or PacketUtils.TcpFlag.PSH, chunk)
                    sndNxt = (sndNxt + chunk.size) and 0xFFFFFFFFL
                }
            }
            // server closed → FIN to client
            synchronized(lock) {
                if (state != State.CLOSED) {
                    sendSegment(PacketUtils.TcpFlag.ACK or PacketUtils.TcpFlag.FIN, ByteArray(0))
                    sndNxt = (sndNxt + 1) and 0xFFFFFFFFL
                    state = State.CLOSING
                }
            }
        } catch (_: Exception) {
            reset()
        }
    }

    private fun sendSegment(flags: Int, payload: ByteArray) {
        val pkt = PacketUtils.buildTcp(
            src = serverIp, srcPort = serverPort,
            dst = clientIp, dstPort = clientPort,
            seq = sndNxt, ack = rcvNxt, flags = flags, window = WIN, payload = payload
        )
        tunnel.writeToTun(pkt)
    }

    private fun reset() {
        synchronized(lock) { closeLocked(sendRst = true) }
    }

    private fun closeLocked(sendRst: Boolean) {
        if (state == State.CLOSED) return
        if (sendRst) {
            try { sendSegment(PacketUtils.TcpFlag.RST or PacketUtils.TcpFlag.ACK, ByteArray(0)) } catch (_: Exception) {}
        }
        state = State.CLOSED
        try { upstream?.close() } catch (_: Exception) {}
        tunnel.onConnectionClosed(key, udp = false)
    }

    fun close() = synchronized(lock) { closeLocked(sendRst = false) }
}
