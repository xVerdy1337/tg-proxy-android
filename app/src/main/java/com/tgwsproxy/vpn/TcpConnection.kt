package com.tgwsproxy.vpn

import androidx.annotation.VisibleForTesting
import java.io.DataInputStream
import java.io.IOException
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
    private val lock = java.lang.Object()

    /** True if the flow is closed or has seen no traffic for [idleMs] (reaped by the service). */
    fun isIdle(idleMs: Long): Boolean =
        state == State.CLOSED || (System.currentTimeMillis() - lastUsed) > idleMs

    private var rcvNxt = 0L            // next client seq we expect
    private var sndNxt = 0L            // our next seq to send to client
    private var clientWindow = 65535
    private var clientAck = 0L         // latest ACK from the client (for window clamping)
    private var seenClientAck = false

    private var upstream: Socket? = null
    private var upOut: OutputStream? = null
    @Volatile private var connected = false
    private val pendingToUpstream = ArrayList<ByteArray>()
    private var pendingToUpstreamBytes = 0
    // Single-writer handoff for upstream writes: blocking socket I/O runs outside the lock,
    // one writer at a time, so queued chunks keep their order.
    private var upstreamWriterActive = false

    companion object {
        private const val MSS = 1400
        private const val WIN = 65535
        // Max wait for the local byedpi SOCKS5 reply before giving up (frees the relay thread).
        private const val SOCKS_HANDSHAKE_TIMEOUT_MS = 10_000
        private const val MAX_PENDING_UPSTREAM_BYTES = 512 * 1024
        // Lost-notification safety net; normal ACK/window updates wake the waiter immediately.
        private const val WINDOW_WAIT_TIMEOUT_MS = 1_000L
    }

    /** Called by the service when the initial SYN for this flow arrives. */
    fun onSyn(clientSeq: Long, window: Int) {
        val synAck = synchronized(lock) {
            rcvNxt = (clientSeq + 1) and 0xFFFFFFFFL
            sndNxt = (Random.nextLong() and 0xFFFFFFFFL)
            clientWindow = window
            val pkt = buildSegmentLocked(PacketUtils.TcpFlag.SYN or PacketUtils.TcpFlag.ACK, ByteArray(0), mss = MSS)
            sndNxt = (sndNxt + 1) and 0xFFFFFFFFL
            pkt
        }
        tunnel.writeToTun(synAck)
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
                // Keepalive as a backstop against dead peers; actual teardown of idle flows still
                // relies on the service's reaper — SO_KEEPALIVE's default ~2h cadence is far too
                // slow to reclaim a mobile VPN's relay threads.
                try { s.keepAlive = true } catch (_: Exception) {}

                tunnel.onConnectResult(true)
                val toUpstream = ArrayList<ByteArray>()
                var closedEarly = false
                synchronized(lock) {
                    if (state == State.CLOSED) {
                        closedEarly = true // the client gave up while the SOCKS handshake ran
                    } else {
                        upstream = s
                        upOut = s.getOutputStream()
                        connected = true
                        drainUpstreamLocked(toUpstream)
                    }
                }
                if (closedEarly) {
                    try { s.close() } catch (_: Exception) {}
                    return@execute
                }
                tunnel.reportError("") // a flow reached the real server → clear stale diagnostics
                if (toUpstream.isNotEmpty()) writeUpstream(toUpstream)
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
    @VisibleForTesting
    internal fun socks5Connect(rawIn: InputStream, out: OutputStream, ip: ByteArray, port: Int): Boolean {
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
        val toTun = ArrayList<ByteArray>(2)
        val toUpstream = ArrayList<ByteArray>(1)
        synchronized(lock) {
            onPacketLocked(seq, ack, flags, window, payload, toTun, toUpstream)
        }
        // Blocking writes (TUN + upstream socket) run outside the lock so a slow write can't
        // stall packet handling; ordering is fixed by the build/queue order under the lock.
        writeAllToTun(toTun)
        if (toUpstream.isNotEmpty()) writeUpstream(toUpstream)
    }

    private fun onPacketLocked(
        seq: Long, ack: Long, flags: Int, window: Int, payload: ByteArray,
        toTun: MutableList<ByteArray>, toUpstream: MutableList<ByteArray>,
    ) {
        if (state == State.CLOSED) return
        lastUsed = System.currentTimeMillis()
        clientWindow = window
        if (flags and PacketUtils.TcpFlag.ACK != 0) {
            clientAck = ack
            seenClientAck = true
            lock.notifyAll()
        }

        if (flags and PacketUtils.TcpFlag.RST != 0) { closeLocked(sendRst = false, toTun); return }
        if (flags and PacketUtils.TcpFlag.SYN != 0) {
            // Client retransmitted its SYN — resend the SYN-ACK (with the MSS option, same as
            // the original sent in onSyn) without consuming a sequence number.
            val saved = sndNxt
            sndNxt = (sndNxt - 1) and 0xFFFFFFFFL
            toTun.add(buildSegmentLocked(PacketUtils.TcpFlag.SYN or PacketUtils.TcpFlag.ACK, ByteArray(0), mss = MSS))
            sndNxt = saved
            return
        }
        if (state == State.SYN_RECEIVED) state = State.ESTABLISHED

        if (payload.isNotEmpty()) {
            when {
                seq == rcvNxt -> {
                    if (queueToUpstreamLocked(payload, toUpstream)) {
                        rcvNxt = (rcvNxt + payload.size) and 0xFFFFFFFFL
                        toTun.add(buildSegmentLocked(PacketUtils.TcpFlag.ACK, ByteArray(0)))
                    } else {
                        closeLocked(sendRst = true, toTun)
                    }
                }
                else -> {
                    toTun.add(buildSegmentLocked(PacketUtils.TcpFlag.ACK, ByteArray(0)))
                }
            }
        }

        if (flags and PacketUtils.TcpFlag.FIN != 0) {
            // A FIN consumes one sequence number, so accept it only in-sequence — an early /
            // out-of-order FIN must not advance rcvNxt or half-close the upstream.
            if (seq + payload.size == rcvNxt) {
                rcvNxt = (rcvNxt + 1) and 0xFFFFFFFFL
                toTun.add(buildSegmentLocked(PacketUtils.TcpFlag.ACK, ByteArray(0)))
                try { upstream?.shutdownOutput() } catch (_: Exception) {}
            }
        }

        // pumpDownstream moved us to CLOSING after sending its FIN; the client's ACK covering
        // that FIN (ack == sndNxt) ends the flow now instead of waiting for the reaper.
        if (state == State.CLOSING && flags and PacketUtils.TcpFlag.ACK != 0 && ack == sndNxt) {
            closeLocked(sendRst = false, toTun)
        }
    }

    /**
     * Queues [data] for the upstream socket and, if no writer is active, drains the queue into
     * [out] for the caller to write after releasing the lock. Returns false when the pending
     * buffer cap is exceeded (the caller then resets the flow).
     */
    private fun queueToUpstreamLocked(data: ByteArray, out: MutableList<ByteArray>): Boolean {
        if (pendingToUpstreamBytes + data.size > MAX_PENDING_UPSTREAM_BYTES) return false
        pendingToUpstream.add(data)
        pendingToUpstreamBytes += data.size
        drainUpstreamLocked(out)
        return true
    }

    /** Moves queued upstream bytes into [out] and marks this thread as the active writer. */
    private fun drainUpstreamLocked(out: MutableList<ByteArray>) {
        if (!connected || upstreamWriterActive || pendingToUpstream.isEmpty()) return
        upstreamWriterActive = true
        while (pendingToUpstream.isNotEmpty()) {
            val chunk = pendingToUpstream.removeAt(0)
            pendingToUpstreamBytes -= chunk.size
            out.add(chunk)
        }
    }

    /**
     * Writes drained chunks to the upstream socket OUTSIDE [lock] — a blocking socket write under
     * the lock would stall all packet handling for this flow. Only one writer runs at a time
     * ([upstreamWriterActive] handoff), which preserves chunk order; after each batch the queue
     * is re-drained until empty. A failed write resets the flow.
     */
    private fun writeUpstream(first: List<ByteArray>) {
        var chunks = first
        try {
            while (true) {
                var stream: OutputStream? = null
                var closed = false
                synchronized(lock) {
                    if (state == State.CLOSED) {
                        upstreamWriterActive = false
                        closed = true
                    } else {
                        stream = upOut
                    }
                }
                if (closed) return
                // connected implies upOut was published under the same lock we just held.
                val out = stream ?: throw IOException("upstream stream missing")
                // No Kotlin-side desync: byedpi reframes/fakes the ClientHello on its outbound socket.
                for (chunk in chunks) out.write(chunk)
                var done = false
                val more = ArrayList<ByteArray>()
                synchronized(lock) {
                    if (pendingToUpstream.isEmpty()) {
                        upstreamWriterActive = false
                        done = true
                    } else {
                        while (pendingToUpstream.isNotEmpty()) {
                            val chunk = pendingToUpstream.removeAt(0)
                            pendingToUpstreamBytes -= chunk.size
                            more.add(chunk)
                        }
                    }
                }
                if (done) return
                chunks = more
            }
        } catch (e: Exception) {
            synchronized(lock) { upstreamWriterActive = false }
            reset()
        }
    }

    @VisibleForTesting
    internal fun downstreamAvailableWindow(inFlight: Long, advertisedWindow: Int, hasAck: Boolean): Long =
        if (hasAck) advertisedWindow.toLong() - inFlight else Long.MAX_VALUE

    /** Reads from byedpi (real server data) and streams it back to the client as TCP segments. */
    private fun pumpDownstream(inp: InputStream) {
        val buf = ByteArray(MSS)
        try {
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val chunk = buf.copyOf(n)
                var sent = 0
                while (sent < chunk.size) {
                    val seg = synchronized(lock) {
                        while (state != State.CLOSED) {
                            // Honor the client-advertised receive window with simple clamping:
                            // in-flight (sent-but-unacked) bytes stay below clientWindow. A zero or
                            // exhausted window must never be bypassed. ACK processing calls notifyAll,
                            // while the timeout is only a lost-notification safety net.
                            val inFlight = (sndNxt - clientAck) and 0xFFFFFFFFL
                            val avail = if (seenClientAck) clientWindow.toLong() - inFlight else Long.MAX_VALUE
                            if (avail > 0) {
                                val take = minOf((chunk.size - sent).toLong(), avail).toInt()
                                val pkt = PacketUtils.buildTcp(
                                    src = serverIp, srcPort = serverPort,
                                    dst = clientIp, dstPort = clientPort,
                                    seq = sndNxt, ack = rcvNxt,
                                    flags = PacketUtils.TcpFlag.ACK or PacketUtils.TcpFlag.PSH,
                                    window = WIN,
                                    payload = chunk.copyOfRange(sent, sent + take),
                                )
                                sndNxt = (sndNxt + take) and 0xFFFFFFFFL
                                sent += take
                                lastUsed = System.currentTimeMillis()
                                return@synchronized pkt
                            }
                            try {
                                lock.wait(WINDOW_WAIT_TIMEOUT_MS)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@synchronized null
                            }
                        }
                        null
                    }
                    if (seg != null) {
                        tunnel.writeToTun(seg)
                    } else if (state == State.CLOSED || Thread.currentThread().isInterrupted) {
                        return
                    }
                }
            }
            val fin = synchronized(lock) {
                if (state != State.CLOSED) {
                    val pkt = buildSegmentLocked(PacketUtils.TcpFlag.ACK or PacketUtils.TcpFlag.FIN, ByteArray(0))
                    sndNxt = (sndNxt + 1) and 0xFFFFFFFFL
                    state = State.CLOSING
                    pkt
                } else {
                    null
                }
            }
            if (fin != null) tunnel.writeToTun(fin)
        } catch (_: Exception) {
            reset()
        }
    }

    /**
     * Builds the next outbound segment. Call only under [lock]; the caller writes the packet to
     * the TUN after releasing the lock (writeToTun may block and must not run under the lock).
     */
    private fun buildSegmentLocked(flags: Int, payload: ByteArray, mss: Int = 0): ByteArray =
        PacketUtils.buildTcp(
            src = serverIp, srcPort = serverPort,
            dst = clientIp, dstPort = clientPort,
            seq = sndNxt, ack = rcvNxt, flags = flags, window = WIN, payload = payload, mss = mss
        )

    private fun writeAllToTun(pkts: List<ByteArray>) {
        for (pkt in pkts) tunnel.writeToTun(pkt)
    }

    private fun reset() {
        val toTun = ArrayList<ByteArray>(1)
        synchronized(lock) { closeLocked(sendRst = true, toTun) }
        writeAllToTun(toTun)
    }

    private fun closeLocked(sendRst: Boolean, toTun: MutableList<ByteArray>) {
        if (state == State.CLOSED) return
        if (sendRst) {
            try { toTun.add(buildSegmentLocked(PacketUtils.TcpFlag.RST or PacketUtils.TcpFlag.ACK, ByteArray(0))) } catch (_: Exception) {}
        }
        state = State.CLOSED
        lock.notifyAll()
        pendingToUpstream.clear()
        pendingToUpstreamBytes = 0
        try { upstream?.close() } catch (_: Exception) {}
        tunnel.onConnectionClosed(key, udp = false)
    }

    fun close() {
        val toTun = ArrayList<ByteArray>(1)
        synchronized(lock) { closeLocked(sendRst = false, toTun) }
        writeAllToTun(toTun)
    }
}
