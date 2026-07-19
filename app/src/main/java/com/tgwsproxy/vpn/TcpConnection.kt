package com.tgwsproxy.vpn

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

/**
 * One TCP flow from a captured app, terminated locally and relayed to the real destination
 * **through the bundled native byedpi engine** running as a local SOCKS5 proxy on
 * 127.0.0.1:[socksPort]. byedpi performs the real packet-level DPI desync (fake/split/disorder/
 * tlsrec with low-TTL + splice fooling) that a pure-Kotlin kernel socket cannot do — that's what
 * defeats the provider's DPI on YouTube/Instagram (same engine alt12/zapret uses on desktop).
 *
 * The hop to 127.0.0.1 is loopback, so it never re-enters our TUN. byedpi's own upstream socket
 * belongs to our app, which is excluded from the VPN (see DesyncVpnService), so it reaches the
 * network directly — no routing loop.
 *
 * This is a deliberately compact, "good-enough" TCP: it ACKs in-order data, ignores out-of-order
 * segments (the client retransmits), and relies on the local TUN write being reliable. It is NOT a
 * full RFC 793 stack — it's the standard lightweight tun2socks-style approach.
 */
class TcpConnection(
    private val clientIp: ByteArray,
    private val clientPort: Int,
    private val serverIp: ByteArray,
    private val serverPort: Int,
    private val tunnel: Tunnel,
    private val key: Long,
    private val socksPort: Int,   // local byedpi SOCKS5 port (127.0.0.1)
) {
    private enum class State { SYN_RECEIVED, ESTABLISHED, CLOSING, CLOSED }

    @Volatile private var state = State.SYN_RECEIVED
    @Volatile var lastUsed = System.currentTimeMillis(); private set
    private val lock = Any()

    /** True if the flow is closed or has seen no traffic for [idleMs] (reaped by the service). */
    fun isIdle(idleMs: Long): Boolean =
        state == State.CLOSED || (System.currentTimeMillis() - lastUsed) > idleMs

    private var rcvNxt = 0L            // next client seq we expect
    private var sndNxt = 0L            // our next seq to send to client
    private var clientWindow = 65535

    private var upstream: Socket? = null
    private var upOut: OutputStream? = null
    @Volatile private var connected = false
    private val pendingToUpstream = ArrayList<ByteArray>()
    private var pendingToUpstreamBytes = 0

    companion object {
        private const val MSS = 1400
        private const val WIN = 65535
        // Max wait for the local byedpi SOCKS5 reply before giving up (frees the relay thread).
        private const val SOCKS_HANDSHAKE_TIMEOUT_MS = 10_000
        private const val MAX_PENDING_UPSTREAM_BYTES = 512 * 1024
    }

    /** Called by the service when the initial SYN for this flow arrives. */
    fun onSyn(clientSeq: Long, window: Int) {
        synchronized(lock) {
            rcvNxt = (clientSeq + 1) and 0xFFFFFFFFL
            sndNxt = (Random.nextLong() and 0xFFFFFFFFL)
            clientWindow = window
            sendSegment(PacketUtils.TcpFlag.SYN or PacketUtils.TcpFlag.ACK, ByteArray(0), mss = MSS)
            sndNxt = (sndNxt + 1) and 0xFFFFFFFFL
        }
        connectUpstream()
    }

    private fun connectUpstream() {
        tunnel.relayExecutor.execute {
            try {
                // Connect to the local byedpi SOCKS5 proxy. Loopback is never routed through the
                // TUN, so no protect() is needed (and protecting loopback would be a no-op anyway).
                val s = Socket()
                s.tcpNoDelay = true
                try {
                    s.connect(InetSocketAddress("127.0.0.1", socksPort), 10_000)
                } catch (e: Exception) {
                    tunnel.reportError("byedpi SOCKS connect → ${e.javaClass.simpleName}: ${e.message}")
                    tunnel.onConnectResult(false)
                    reset(); return@execute
                }

                // Bound the SOCKS handshake read: a hung byedpi must not pin this relay thread
                // forever (thread-pool starvation). Cleared below for the long-lived relay so idle
                // keep-alive flows aren't torn down.
                try { s.soTimeout = SOCKS_HANDSHAKE_TIMEOUT_MS } catch (_: Exception) {}

                // SOCKS5 CONNECT to the real destination; byedpi then desyncs + dials out.
                val ok = try {
                    socks5Connect(s.getInputStream(), s.getOutputStream(), serverIp, serverPort)
                } catch (e: Exception) {
                    tunnel.reportError("SOCKS5 ${PacketUtils.ipToString(serverIp)}:$serverPort → ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                if (!ok) {
                    tunnel.onConnectResult(false)
                    try { s.close() } catch (_: Exception) {}
                    reset(); return@execute
                }
                try { s.soTimeout = 0 } catch (_: Exception) {} // back to blocking for the relay

                tunnel.onConnectResult(true)
                synchronized(lock) {
                    upstream = s
                    upOut = s.getOutputStream()
                    connected = true
                    flushPendingLocked()
                }
                tunnel.reportError("") // a flow reached the real server → clear stale diagnostics
                pumpDownstream(s.getInputStream())
            } catch (e: Exception) {
                tunnel.reportError("upstream ${e.javaClass.simpleName}: ${e.message}")
                reset()
            }
        }
    }

    /**
     * Minimal SOCKS5 client: no-auth greeting + CONNECT to an IPv4 destination. Returns true on a
     * success reply (REP == 0x00).
     */
    private fun socks5Connect(rawIn: InputStream, out: OutputStream, ip: ByteArray, port: Int): Boolean {
        val din = DataInputStream(rawIn)
        // Greeting: VER=5, NMETHODS=1, METHOD=0 (no auth)
        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        val ver = din.readUnsignedByte()
        val method = din.readUnsignedByte()
        if (ver != 0x05 || method != 0x00) return false
        // CONNECT: VER=5, CMD=1, RSV=0, ATYP=1 (IPv4), DST.ADDR(4), DST.PORT(2)
        val req = ByteArray(10)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01
        System.arraycopy(ip, 0, req, 4, 4)
        req[8] = ((port ushr 8) and 0xFF).toByte()
        req[9] = (port and 0xFF).toByte()
        out.write(req); out.flush()
        // Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT
        if (din.readUnsignedByte() != 0x05) return false
        val rep = din.readUnsignedByte()
        din.readUnsignedByte() // RSV
        val atyp = din.readUnsignedByte()
        val addrLen = when (atyp) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> din.readUnsignedByte()
            else -> return false
        }
        din.readFully(ByteArray(addrLen + 2)) // BND.ADDR + BND.PORT
        return rep == 0x00
    }

    /** Called by the service for every non-SYN TCP packet belonging to this flow. */
    fun onPacket(seq: Long, ack: Long, flags: Int, window: Int, payload: ByteArray) {
        synchronized(lock) {
            if (state == State.CLOSED) return
            lastUsed = System.currentTimeMillis()
            clientWindow = window

            if (flags and PacketUtils.TcpFlag.RST != 0) { closeLocked(sendRst = false); return }
            if (flags and PacketUtils.TcpFlag.SYN != 0) {
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
                        if (queueToUpstreamLocked(payload)) {
                            rcvNxt = (rcvNxt + payload.size) and 0xFFFFFFFFL
                            sendSegment(PacketUtils.TcpFlag.ACK, ByteArray(0))
                        } else {
                            closeLocked(sendRst = true)
                        }
                    }
                    else -> {
                        sendSegment(PacketUtils.TcpFlag.ACK, ByteArray(0))
                    }
                }
            }

            if (flags and PacketUtils.TcpFlag.FIN != 0) {
                if (seq + payload.size == rcvNxt || payload.isEmpty()) {
                    rcvNxt = (rcvNxt + 1) and 0xFFFFFFFFL
                    sendSegment(PacketUtils.TcpFlag.ACK, ByteArray(0))
                    try { upstream?.shutdownOutput() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun queueToUpstreamLocked(data: ByteArray): Boolean {
        if (!connected) {
            if (pendingToUpstreamBytes + data.size > MAX_PENDING_UPSTREAM_BYTES) return false
            pendingToUpstream.add(data)
            pendingToUpstreamBytes += data.size
            return true
        }
        return writeUpstreamLocked(data)
    }

    private fun flushPendingLocked() {
        val copy = ArrayList(pendingToUpstream)
        pendingToUpstream.clear()
        pendingToUpstreamBytes = 0
        for (chunk in copy) if (!writeUpstreamLocked(chunk)) break
    }

    private fun writeUpstreamLocked(data: ByteArray): Boolean {
        val out = upOut ?: return false
        // No Kotlin-side desync: byedpi reframes/fakes the ClientHello on its outbound socket.
        return try {
            out.write(data)
            true
        } catch (_: Exception) {
            closeLocked(sendRst = true)
            false
        }
    }

    /** Reads from byedpi (real server data) and streams it back to the client as TCP segments. */
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
                    lastUsed = System.currentTimeMillis()
                    sendSegment(PacketUtils.TcpFlag.ACK or PacketUtils.TcpFlag.PSH, chunk)
                    sndNxt = (sndNxt + chunk.size) and 0xFFFFFFFFL
                }
            }
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

    private fun sendSegment(flags: Int, payload: ByteArray, mss: Int = 0) {
        val pkt = PacketUtils.buildTcp(
            src = serverIp, srcPort = serverPort,
            dst = clientIp, dstPort = clientPort,
            seq = sndNxt, ack = rcvNxt, flags = flags, window = WIN, payload = payload, mss = mss
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
        pendingToUpstream.clear()
        pendingToUpstreamBytes = 0
        try { upstream?.close() } catch (_: Exception) {}
        tunnel.onConnectionClosed(key, udp = false)
    }

    fun close() = synchronized(lock) { closeLocked(sendRst = false) }
}
