package com.tgwsproxy.proxy

import android.content.Context
import android.os.SystemClock
import com.tgwsproxy.R
import com.tgwsproxy.service.LogKind
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MtProtoProxyServer(
    private val appContext: Context,
    private val host: String,
    private val port: Int,
    private val secret: String,
    private val onLog: (String, LogKind) -> Unit,
    private val onConnectionChange: (Int) -> Unit,
    // Optional user-supplied Cloudflare-proxy domain(s), separated by comma / space / semicolon
    // (e.g. "mydomain.com, other.com"). When set they are tried FIRST in the CF fallback, in the
    // given order, before the bundled defaults.
    private val cfDomain: String = "",
    // Optional Cloudflare Worker domain(s) (comma / space / semicolon separated), e.g.
    // "name.username.workers.dev". A user-deployed CF Worker relays a WebSocket to a RAW
    // Telegram DC IP over Cloudflare's edge (free alternative to a purchased CF-proxy domain —
    // no domain needed). Tried as an extra fallback tier when set. Empty = disabled.
    private val cfWorkerDomain: String = "",
    // Optional Fake-TLS masking domain. When non-empty, clients connect with an `ee...`
    // secret and wrap the obfuscated2 stream in TLS records that look like HTTPS to [this
    // domain] — the strongest DPI bypass. Empty = plain (dd) handshake on the raw path.
    private val fakeTlsDomain: String = "",
    // Called when relay bytes move so the owner can keep the CPU awake only for active I/O.
    private val onTraffic: () -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val activeConnections = ConcurrentHashMap.newKeySet<Socket>()
    private val activeWebSockets = ConcurrentHashMap.newKeySet<WebSocketBridge>()
    private val connectionCount = AtomicInteger(0)
    // Per-connection sequence for log labels: on loopback every client is 127.0.0.1, so the bare
    // address made all summary lines indistinguishable.
    private val connectionSeq = AtomicInteger(0)
    // Direct-wave backoff, dead-open hosts, and the single-flight re-probe. An onOpen is not
    // a success: only a downstream byte clears the backoff, and a host that upgrades and then
    // sends nothing is skipped on the next dial. See [UpstreamRouteGate].
    private val routeGate = UpstreamRouteGate()
    private val lastTrafficSignalAt = AtomicLong(0)

    private fun signalTraffic() {
        val now = SystemClock.uptimeMillis()
        val previous = lastTrafficSignalAt.get()
        if (now - previous >= TRAFFIC_SIGNAL_MIN_INTERVAL_MS &&
            lastTrafficSignalAt.compareAndSet(previous, now)
        ) {
            onTraffic()
        }
    }

    /**
     * Live client count, readable at any moment from any thread.
     *
     * The number delivered to `onConnectionChange` is a SNAPSHOT taken by whichever thread did the
     * increment or decrement, and the atomic update and the callback are two separate steps — so two
     * callbacks racing from different threads arrive in an order unrelated to the order the counter
     * actually moved, in BOTH directions. A consumer that latches the delivered value can therefore
     * end up believing there are no clients while one is relaying. Anything making a decision on the
     * count must read this instead of trusting the payload.
     */
    val connections: Int get() = connectionCount.get()

    /** True while any live WebSocket still has bytes queued in OkHttp's writer. */
    fun hasPendingWebSocketWrites(): Boolean = activeWebSockets.any { it.queueSize() > 0L }
    // Blocking read()/write() calls pin the thread they run on, so the shared Dispatchers.IO
    // pool would be drained by a full house of clients (~2-3 parked threads per client x
    // MAX_CLIENTS, plus the WS race). Give the server its own cached pool instead: threads are
    // created on demand, reclaimed when idle, and shut down together with the scope in stop().
    // Both are recreated in start() so the instance stays restartable after stop().
    private var serverDispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private var serverScope = CoroutineScope(serverDispatcher + SupervisorJob())
    private val secretBytes = parseSecret(secret)

    // === Live traffic stats (read by the service for the UI) ===
    val bytesUp = AtomicLong(0)     // client -> telegram (uploaded)
    val bytesDown = AtomicLong(0)   // telegram -> client (downloaded)
    @Volatile var lastRoute: String = ""   // e.g. "cloudflare", "direct", "tcp"

    // Raw MTProto core IPs — used ONLY for the TCP fallback (port 443/raw obfuscated2).
    private val dcDefaultIps = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100"
    )

    // Web-front IPs that actually serve the kwsN.web.telegram.org /apiws WebSocket endpoint.
    // The raw MTProto IPs above do NOT serve /apiws, so the WS connect must target these.
    private val wsFrontIps = mapOf(
        1 to "149.154.174.100",
        2 to "149.154.167.99",
        3 to "149.154.174.100",
        4 to "149.154.167.99",
        5 to "149.154.170.100",
        203 to "149.154.167.99"
    )

    // Cloudflare-proxy fallback domains. Each is fronted by Cloudflare: kwsN.<domain>
    // resolves to Cloudflare anycast IPs (NOT Telegram IPs), and Cloudflare proxies the
    // /apiws WebSocket through to Telegram. This is what makes the proxy survive networks
    // that block Telegram's IP ranges directly (DPI / TSPU).
    // Bundled CF-fronting domain labels are Caesar-shifted (see CF_SHIFT) and decoded at
    // runtime. This is an anti-scraping measure, NOT anti-reverse (issue #39): the decoder
    // sits right below, so reversing is trivial and we accept that. The actual goal is to
    // keep plaintext domains out of the APK string table so censors' bulk APK-scraping bots
    // can't harvest them via `strings`/grep into automatic blocklists. Real domain rotation
    // is handled by userCfDomains / cfWorkerDomain, not by this encoding.
    private val encodedCfLabels = listOf(
        "uvzrvtuhkgvy",
        "rhyavzorh",
        "jhrlpzhspl",
        "svclaybl",
        "zvyvrkch",
        "ohclnylhakhf",
        "wvtvnpal",
        "wjslhk",
        "vmmzovy"
    )

    // The decoder being trivially invertible is a documented, accepted trade-off — see the
    // rationale at encodedCfLabels (anti-scraping of APK strings, not anti-reverse).
    private fun decodeCfLabel(s: String): String = buildString {
        for (c in s) append(if (c in 'a'..'z') 'a' + ((c - 'a' + 26 - CF_SHIFT) % 26) else c)
    }

    private val defaultCfDomains: List<String> by lazy {
        encodedCfLabels.map { decodeCfLabel(it) + ".co.uk" }
    }

    // User-supplied CF / Worker domains: split on comma / semicolon / space, trimmed, de-duplicated.
    private val userCfDomains: List<String> by lazy { splitDomains(cfDomain) }
    private val userCfWorkerDomains: List<String> by lazy { splitDomains(cfWorkerDomain) }

    private fun splitDomains(s: String): List<String> =
        s.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun start() {
        running = true
        // Recreate scope + dispatcher: stop() cancels the scope and shuts the pool down, and a
        // cancelled SupervisorJob would silently drop every coroutine launched after a restart.
        serverDispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
        serverScope = CoroutineScope(serverDispatcher + SupervisorJob())
        try {
            serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName(host))
        } catch (e: Exception) {
            // Bind failed (port busy, bad address): leave no half-started state behind —
            // undo the fresh scope/dispatcher, clear `running`, and let the caller see it.
            running = false
            serverScope.cancel()
            serverDispatcher.close()
            onLog("Failed to bind $host:$port: ${e.message}", LogKind.ERROR)
            throw e
        }
        serverSocket?.receiveBufferSize = 256 * 1024
        onLog(appContext.getString(R.string.proxy_listening, host, port), LogKind.PLAIN)

        while (running) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                if (!running) {
                    clientSocket.close()
                    break
                }
                // Cap concurrent clients. Telegram opens at most ~15; anything beyond MAX_CLIENTS on
                // a loopback proxy is a local flood (each client fans out up to ~18 upstream TLS
                // dials), so drop it rather than exhaust fds/threads.
                // The increment IS the admission decision: get()-then-incrementAndGet() is
                // check-then-act, so two admissions that observe the same pre-increment value both
                // pass a cap that only has room for one. Reserve first, hand the slot back on refusal.
                val count = connectionCount.incrementAndGet()
                if (count > MAX_CLIENTS) {
                    connectionCount.decrementAndGet()
                    try { clientSocket.close() } catch (_: Exception) {}
                    continue
                }
                activeConnections.add(clientSocket)
                onConnectionChange(count)

                serverScope.launch {
                    handleClient(clientSocket)
                }
            } catch (e: SocketException) {
                if (running) {
                    // Listener died without stop() being called — reflect that in `running`
                    // instead of leaving it stuck at true with no accept loop behind it.
                    running = false
                    onLog("Socket error: ${e.message}", LogKind.ERROR)
                }
                break
            } catch (e: IOException) {
                onLog("IO error: ${e.message}", LogKind.WARNING)
            }
        }
    }

    fun stop() {
        running = false
        serverScope.cancel()
        // Shut the dedicated pool down with the scope; start() creates a fresh pair, so the
        // instance remains restartable.
        serverDispatcher.close()
        routeCache.clear()
        activeConnections.forEach { try { it.close() } catch (_: Exception) {} }
        activeConnections.clear()
        activeWebSockets.forEach { try { it.close() } catch (_: Exception) {} }
        activeWebSockets.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        onLog(appContext.getString(R.string.proxy_stopped_log), LogKind.PLAIN)
    }

    /**
     * Force every active upstream session to redial. Called when the device switches
     * network (Wi-Fi ↔ mobile): the old upstream WS/TCP sockets are bound to the gone
     * network and would just hang, so we drop the client connections and let Telegram
     * re-open them through the local proxy over the new network — instant reconnect
     * instead of a stall until the user reopens the app.
     */
    fun resetConnections() {
        val n = activeConnections.size
        activeConnections.forEach { try { it.close() } catch (_: Exception) {} }
        activeConnections.clear()
        // Re-evaluate routes on the new network: an endpoint that worked on Wi-Fi may be
        // blocked on mobile (or vice-versa). Drop the cache, the dud strikes, and the
        // direct-wave backoff together — a failure recorded on the old link must not keep
        // the new one on Cloudflare for up to half an hour.
        routeCache.clear()
        routeStrikes.clear()
        routeGate.reset()
        if (n > 0) onLog(appContext.getString(R.string.network_changed, n), LogKind.PLAIN)
    }

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = try { input.read(buf, read, n - read) } catch (_: Exception) { return null }
            if (r <= 0) return null
            read += r
        }
        return buf
    }

    private suspend fun handleClient(clientSocket: Socket) {
        val label = connectionSeq.incrementAndGet().toString()
        // Per-connection observability: one CONN summary line at close instead of per-phase
        // chatter. Counters are cheap lock-free atomics bumped in the relay hot paths; the
        // close reason is written from relay coroutines, so it needs the atomic reference.
        val startTime = System.currentTimeMillis()
        val connUp = AtomicLong(0)
        val connDown = AtomicLong(0)
        val closeReason = AtomicReference("client-gone")
        var handshakeDone = false
        var connDcId = 0
        var connIsMedia = false
        var route = ""
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.keepAlive = true
            clientSocket.receiveBufferSize = 256 * 1024
            clientSocket.sendBufferSize = 256 * 1024
            // Bound the handshake phase so a silent/half-open peer can't pin a coroutine forever.
            clientSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

            val rawInput = clientSocket.getInputStream()
            val rawOutput = clientSocket.getOutputStream()

            // Streams the rest of the pipeline reads/writes — possibly wrapped in Fake TLS.
            var clientInput: InputStream = rawInput
            var clientOutput: OutputStream = rawOutput

            // Read the first byte to tell a TLS ClientHello (Fake TLS) from a raw obfs2 init.
            val firstByte = readFully(rawInput, 1)
            if (firstByte == null) {
                onLog("[$label] disconnected before handshake", LogKind.DEBUG)
                return
            }

            val handshake: ByteArray
            // Fake-TLS detection keys on the TLS record type byte 0x16. A raw obfs2 init starts
            // with 64 random bytes, so its first byte collides with 0x16 in ~1/256 of cases and
            // such a connection would be misclassified as Fake TLS. Inherent protocol property,
            // accepted: when fakeTlsDomain is set clients are expected to use an `ee...` secret,
            // and a misclassified raw attempt just fails verify and is relayed to the masking
            // domain (see maskingRelay).
            if (fakeTlsDomain.isNotEmpty() && (firstByte[0].toInt() and 0xFF) == FakeTls.TLS_RECORD_HANDSHAKE) {
                // --- Fake TLS path ---
                val hdrRest = readFully(rawInput, 4)
                if (hdrRest == null) { onLog("[$label] incomplete TLS header", LogKind.DEBUG); return }
                val recLen = ((hdrRest[2].toInt() and 0xFF) shl 8) or (hdrRest[3].toInt() and 0xFF)
                // Cap the pre-auth allocation: a real TLS ClientHello is well under 4 KiB, so reject
                // anything larger before allocating — stops an unauthenticated peer from forcing a
                // 64 KiB buffer + HMAC per connection (memory/CPU amplification).
                if (recLen > MAX_CLIENT_HELLO) { onLog("[$label] TLS record too large ($recLen)", LogKind.DEBUG); return }
                val body = readFully(rawInput, recLen)
                if (body == null) { onLog("[$label] incomplete TLS body", LogKind.DEBUG); return }
                val clientHello = firstByte + hdrRest + body

                val verified = FakeTls.verifyClientHello(clientHello, secretBytes)
                if (verified == null) {
                    // Probe / wrong secret — relay to the real masking domain so we look benign.
                    onLog("[$label] Fake TLS verify failed → masking", LogKind.DEBUG)
                    maskingRelay(clientSocket, rawInput, rawOutput, clientHello, connUp, connDown)
                    return
                }

                val serverHello = FakeTls.buildServerHello(secretBytes, verified)
                rawOutput.write(serverHello)
                rawOutput.flush()

                clientInput = FakeTlsInputStream(rawInput)
                clientOutput = FakeTlsOutputStream(rawOutput)

                val inner = readFully(clientInput, MtProtoConstants.HANDSHAKE_LEN)
                if (inner == null) { onLog("[$label] incomplete obfs2 init inside TLS", LogKind.DEBUG); return }
                handshake = inner
                // Per-connection noise: the HANDSHAKE line below already marks every real session.
                onLog("[$label] Fake TLS handshake ok", LogKind.DEBUG)
            } else {
                // --- Raw obfuscated2 path: first byte + remaining 63. ---
                val rest = readFully(rawInput, MtProtoConstants.HANDSHAKE_LEN - 1)
                if (rest == null) { onLog("[$label] disconnected before handshake", LogKind.DEBUG); return }
                handshake = firstByte + rest
            }

            // Try handshake
            val result = MtProtoHandshake.tryHandshake(handshake, secretBytes)
            if (result == null) {
                onLog("[$label] bad handshake (wrong secret or proto)", LogKind.DEBUG)
                // Bad handshake: close immediately. Do NOT skip() — reading from a silent peer
                // could block forever (DoS); the finally block closes the socket.
                return
            }
            // Handshake done — go back to blocking reads for the long-lived relay phase.
            clientSocket.soTimeout = 0

            val protoInt = when {
                result.protoTag.contentEquals(MtProtoConstants.PROTO_TAG_ABRIDGED) -> MtProtoConstants.PROTO_ABRIDGED_INT
                result.protoTag.contentEquals(MtProtoConstants.PROTO_TAG_INTERMEDIATE) -> MtProtoConstants.PROTO_INTERMEDIATE_INT
                else -> MtProtoConstants.PROTO_PADDED_INTERMEDIATE_INT
            }

            val dcIdx = if (result.isMedia) -result.dcId else result.dcId
            onLog("[$label] handshake ok: DC${result.dcId}${if (result.isMedia) " media" else ""} proto=0x${protoInt.toString(16)}", LogKind.HANDSHAKE)
            // Session identity for the CONN summary logged in finally.
            connDcId = result.dcId
            connIsMedia = result.isMedia
            handshakeDone = true

            // relayInit is the TELEGRAM-side obfuscation header — sent to Telegram only.
            val relayInit = MtProtoHandshake.generateRelayInit(result.protoTag, dcIdx)

            val cryptoCtx = MtProtoHandshake.buildCryptoContext(
                result.clientDecPrekeyIv,
                secretBytes,
                relayInit
            )

            // Connect WebSocket by racing every transport candidate; first to open wins.
            val wsConn = connectAnyWs(result.dcId, result.isMedia, label)

            if (wsConn == null) {
                onLog("[$label] WS connection failed, trying TCP fallback", LogKind.WARNING)
                val fallbackIp = dcDefaultIps[result.dcId] ?: dcDefaultIps[2]!!
                route = "tcp"
                // tcpFallback logs its own detailed error line ("failed to connect to … after
                // 5000ms"); a second bare "fallback failed" line here only doubled the noise.
                tcpFallback(clientSocket, clientInput, clientOutput, cryptoCtx, relayInit, fallbackIp, connUp, connDown, closeReason, label)
                return
            }

            val (bridge, candidate, openedAtEpoch) = wsConn
            route = "ws"

            // Hand Telegram the relay obfuscation init as the very first WS frame.
            // Fail fast if it can't be enqueued: the session is useless without it and the
            // finally block would otherwise only close the socket after a pointless wait.
            if (!bridge.send(relayInit)) {
                onLog("[$label] WS bridge closed before relay init — dropping client", LogKind.WARNING)
                activeWebSockets.remove(bridge)
                try { bridge.close() } catch (_: Exception) {}
                return
            }

            bridgeData(clientSocket, clientInput, clientOutput, bridge, cryptoCtx, relayInit, protoInt, label, result.dcId, result.isMedia, candidate, connUp, connDown, closeReason, openedAtEpoch)

        } catch (e: CancellationException) {
            // Never swallow cancellation — it is how stop()/resetConnections() tears sessions down.
            throw e
        } catch (e: Exception) {
            closeReason.compareAndSet("client-gone", "error:${e.message}")
            onLog("[$label] error: ${e.message}", LogKind.ERROR)
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            activeConnections.remove(clientSocket)
            val count = connectionCount.decrementAndGet()
            onConnectionChange(count)
            // One summary per real session — the entry point for media-connection diagnostics.
            // Connections that died before the handshake are probes/scans; their per-phase
            // lines above are DEBUG noise and get no summary.
            if (handshakeDone) {
                val up = connUp.get()
                val down = connDown.get()
                val durSec = (System.currentTimeMillis() - startTime) / 1000.0
                val arrow = if (down >= up) "↓" else "↑"
                onLog(
                    "[#$label] DC$connDcId${if (connIsMedia) " media" else ""} ${route.ifEmpty { "?" }} " +
                        "${formatBytes(up + down)} $arrow ${"%.1f".format(durSec)} s — closed: ${closeReason.get()}",
                    LogKind.CONN
                )
                // Evict a half-dead cached WS route: the edge accepts the connection, then
                // closes it within seconds having relayed next to nothing (peer-initiated,
                // short life, <2 KB total — the field logs showed such sessions CAN carry a
                // few hundred bytes downstream before the edge drops them, so "0 B down" was
                // too strict a dud test). Long-lived or fat sessions are healthy and reset the
                // counter; client-gone churn is Telegram's own and never counts.
                if (route == "ws") {
                    val key = "$connDcId/$connIsMedia"
                    val dud = closeReason.get() == "peer" && durSec < 15 && up + down < 2048
                    if (dud) {
                        val strikes = routeStrikes.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
                        if (strikes >= 2) {
                            routeStrikes.remove(key)
                            if (routeCache.remove(key) != null) {
                                onLog(
                                    "cached WS route for DC$connDcId${if (connIsMedia) " media" else ""} " +
                                        "dropped two fresh sessions in a row — evicted, next session re-races",
                                    LogKind.WARNING
                                )
                            }
                        }
                    } else {
                        routeStrikes.remove(key)
                    }
                }
            }
        }
    }

    /**
     * Relay a failed Fake-TLS probe to the real masking domain on :443 so a censor sees a
     * normal HTTPS session to a legit site instead of a dead-end. Best-effort.
     */
    private suspend fun maskingRelay(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        initialData: ByteArray,
        connUp: AtomicLong,
        connDown: AtomicLong
    ) {
        try {
            // Relaying ANY TLS-looking ClientHello (probe or wrong secret) to the real masking
            // domain is a deliberate part of the camouflage: a censor's active probe gets a
            // genuine HTTPS session with the legit site, indistinguishable from a normal server.
            // Bounded connect so a dead/unreachable masking domain can't pin this coroutine.
            val up = Socket().apply { connect(InetSocketAddress(fakeTlsDomain, 443), UPSTREAM_CONNECT_TIMEOUT_MS) }
            try {
                up.tcpNoDelay = true
                up.soTimeout = RELAY_IO_TIMEOUT_MS
                try { clientSocket.soTimeout = RELAY_IO_TIMEOUT_MS } catch (_: Exception) {}
                val upOut = up.getOutputStream()
                val upIn = up.getInputStream()
                upOut.write(initialData); upOut.flush()

                val a = serverScope.async {
                    try {
                        val b = ByteArray(16384)
                        while (!clientSocket.isClosed) {
                            val r = clientInput.read(b); if (r <= 0) break
                            connUp.addAndGet(r.toLong())
                            signalTraffic()
                            upOut.write(b, 0, r); upOut.flush()
                        }
                    } catch (_: Exception) {}
                }
                val c = serverScope.async {
                    try {
                        val b = ByteArray(16384)
                        while (!up.isClosed) {
                            val r = upIn.read(b); if (r <= 0) break
                            connDown.addAndGet(r.toLong())
                            signalTraffic()
                            clientOutput.write(b, 0, r); clientOutput.flush()
                        }
                    } catch (_: Exception) {}
                }
                select<Unit> {
                    a.onAwait { }
                    c.onAwait { }
                }
                try { clientSocket.close() } catch (_: Exception) {}
                try { up.close() } catch (_: Exception) {}
                a.cancel(); c.cancel()
            } finally {
                // Always drop the upstream socket, even if the relay is cancelled / the coroutine is
                // cancelled mid-relay — otherwise the fd leaks until GC.
                try { up.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** Human-readable byte total for the CONN summary line. */
    private fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
        b >= 1L shl 10 -> "%.1f KB".format(b.toDouble() / (1L shl 10))
        else -> "$b B"
    }

    // Map a relay-loop failure to a close cause for the CONN summary: SO_TIMEOUT means the
    // peer went silent mid-session; anything else keeps its message for diagnosis.
    private fun classifyRelayError(e: Exception): String =
        if (e is SocketTimeoutException) "timeout" else "error:${e.message ?: e.javaClass.simpleName}"

    private data class WsCandidate(val pinnedIp: String?, val host: String, val kind: String, val path: String = "/apiws")

    // Remembers the last endpoint that PROVED it carries Telegram data (set in bridgeData once
    // bytes actually flow back), keyed by "dcId/isMedia". On a reconnect we try it alone first
    // instead of racing ~18 TLS handshakes again — big battery/radio saver, especially when a
    // network switch forces every session to redial at once.
    //
    // NB: we deliberately cache only AFTER data flows, not when the WebSocket merely opens. A
    // Cloudflare-fronted endpoint can complete the WS upgrade (onOpen) while its upstream to
    // Telegram is dead; a WebSocket upgrade alone does not prove the relay works and can leave a
    // "connected but dead" route open. Caching only
    // proven routes + the stall watchdog in bridgeData prevents that.
    private val routeCache = ConcurrentHashMap<String, WsCandidate>()
    // Strikes against a cached WS route that connects fine but closes having relayed nothing.
    // connect() succeeds for such a half-dead edge, so without this it would stay cached forever
    // and every session to that DC would die the same ~5s "closed: peer" death in a loop.
    // Two consecutive dud sessions evict the route, forcing the next connect to re-race the pool.
    private val routeStrikes = ConcurrentHashMap<String, AtomicInteger>()

    private data class OpenedWs(
        val bridge: WebSocketBridge,
        val candidate: WsCandidate,
        val epoch: Int,
    )

    private suspend fun connectAnyWs(dcId: Int, isMedia: Boolean, label: String): OpenedWs? {
        // Taken before any dial. bridgeData records success and dead-opens against this
        // value, so a reset that lands while we are still dialling cannot be overwritten
        // by the attempt that just lost its network.
        val epoch = routeGate.epoch()
        val cacheKey = "$dcId/$isMedia"

        routeCache[cacheKey]?.let { cached ->
            if (routeGate.isDeadOpen(cached.host)) {
                routeCache.remove(cacheKey, cached)
            } else {
                val b = WebSocketBridge { signalTraffic() }
                val ok = try { b.connect(cached.pinnedIp, cached.host, cached.path) } catch (_: Exception) { false }
                if (ok) {
                    lastRoute = cached.kind
                    activeWebSockets.add(b)
                    onLog("[$label] WS reconnected via ${cached.host} (${cached.kind}, cached)", LogKind.WS)
                    return OpenedWs(b, cached, epoch)
                }
                try { b.close() } catch (_: Exception) {}
                routeCache.remove(cacheKey, cached) // cached endpoint went stale — fall back to full race
            }
        }

        val directCandidates = ArrayList<WsCandidate>()
        val wsTargetIp = wsFrontIps[dcId] ?: wsFrontIps[2]!!
        for (domain in MtProtoHandshake.wsDomains(dcId, isMedia)) {
            directCandidates.add(WsCandidate(wsTargetIp, domain, "direct"))
        }

        val cfCandidates = ArrayList<WsCandidate>()
        val cfDc = if (dcId == 203) 2 else dcId
        for (base in userCfDomains + defaultCfDomains.shuffled()) {
            cfCandidates.add(WsCandidate(null, "kws$cfDc.$base", "cloudflare"))
        }

        // Wave 1: race only the few direct web-front endpoints first. In the common case one wins
        // within a second, so we open ~2-3 TLS handshakes instead of ~18 — a big radio/CPU saver,
        // especially when a network switch forces every session to redial at once. Only if the
        // direct wave fails do we fall back to racing the full Cloudflare pool (wave 2).
        //
        // The re-probe after a negative-cache expiry is single-flight: the first connection to
        // notice the expiry dials direct, everyone arriving while it is dialling skips wave 1.
        // Opening the socket does not clear the backoff. A front that finishes the WebSocket
        // handshake and never relays bytes used to win every race, wipe the backoff, and keep
        // Cloudflare from ever being tried. The backoff ends in bridgeData, on the first byte.
        val directFresh = routeGate.directIsFresh()
        if (directFresh && routeGate.tryStartProbe()) {
            try {
                onLog("[$label] connecting WS: ${directCandidates.size} direct endpoints", LogKind.WS)
                val directResult = raceCandidates(directCandidates, WS_WAVE_TIMEOUT_MS, label)
                if (directResult != null) return OpenedWs(directResult.first, directResult.second, epoch)
                routeGate.recordDirectWaveFailure(epoch)
                val skipMin = routeGate.negativeTtlMs() / 60_000
                onLog("[$label] direct failed — racing ${cfCandidates.size} Cloudflare endpoints (skip $skipMin min)", LogKind.CLOUDFLARE)
            } finally {
                routeGate.endProbe(epoch)
            }
        } else {
            val why = if (directFresh) "probe in flight" else "failed recently"
            onLog("[$label] direct skipped ($why) — racing ${cfCandidates.size} Cloudflare endpoints", LogKind.CLOUDFLARE)
        }
        raceCandidates(cfCandidates, WS_RACE_TIMEOUT_MS, label)?.let {
            return OpenedWs(it.first, it.second, epoch)
        }

        // Extra tier: user-deployed Cloudflare Worker(s). Each relays a WebSocket to the RAW DC
        // IP (dst) over Cloudflare's edge — a free alternative to a purchased CF-proxy domain.
        // Only tried when the user configured a worker domain. Same relayInit + obfuscated2 +
        // splitter framing as the raw TCP fallback (the worker just pipes bytes to dst:443).
        if (userCfWorkerDomains.isNotEmpty()) {
            val dcIp = dcDefaultIps[dcId] ?: dcDefaultIps[2]!!
            val workerCandidates = ArrayList<WsCandidate>()
            for (worker in userCfWorkerDomains.shuffled()) {
                workerCandidates.add(
                    WsCandidate(null, worker, "cf_worker", "/apiws?dst=$dcIp&dc=$dcId")
                )
            }
            onLog("[$label] Cloudflare failed — racing ${workerCandidates.size} CF Worker endpoint(s)", LogKind.CLOUDFLARE)
            return raceCandidates(workerCandidates, WS_RACE_TIMEOUT_MS, label)?.let {
                OpenedWs(it.first, it.second, epoch)
            }
        }
        return null
    }

    /** Race [candidates] concurrently, returning the first that connects (losers are closed). */
    private suspend fun raceCandidates(
        candidates: List<WsCandidate>,
        timeoutMs: Long,
        label: String,
    ): Pair<WebSocketBridge, WsCandidate>? {
        // A host that completed a WebSocket upgrade and then relayed nothing is skipped for
        // DEAD_OPEN_TTL. Otherwise the fastest dead handshake wins every race and the fronts
        // that would have carried bytes are closed as losers.
        val pool = candidates.filterNot { routeGate.isDeadOpen(it.host) }
        if (pool.isEmpty()) return null

        // Carry bridge + winning candidate together so there's no race between "who won" and
        // "which endpoint won" (the candidate is needed later to cache a proven route).
        //
        // [winner] is also the sole arbiter of bridge OWNERSHIP: complete() succeeds for exactly
        // one caller, so exactly one candidate keeps its bridge and every other candidate closes
        // the bridge it created itself. The previous shape — register each bridge in a list and
        // sweep the list once the race resolved — stranded live sockets: connect() blocks on a
        // plain CountDownLatch (WebSocketBridge), so cancelling the jobs cannot stop a candidate
        // that is already dialling, and one that registered or finished after the sweep was never
        // closed by anybody. Without explicit ownership cleanup, a whole Cloudflare wave's worth
        // of sockets could remain open on every reconnect.
        val winner = kotlinx.coroutines.CompletableDeferred<Pair<WebSocketBridge, WsCandidate>?>()

        // Per-candidate failure classes, collected so a lost race can say WHY (timeout vs reset
        // vs DNS) instead of a bare "failed". Class name only: exception messages carry
        // per-attempt ports/IPs, which would defeat both the grouping here and the ×N log
        // folding downstream.
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val dialSlots = Semaphore(WS_DIAL_CONCURRENCY)
        val jobs = pool.map { c ->
            serverScope.launch {
                val b = WebSocketBridge { signalTraffic() }
                val ok = try {
                    // Waiting candidates are cancellable; only a small number of blocking OkHttp
                    // handshakes can occupy relay threads and wake the radio at once. Once a slot
                    // is available, every candidate still gets its chance before the wave deadline.
                    dialSlots.withPermit { b.connect(c.pinnedIp, c.host, c.path) }
                } catch (e: Exception) {
                    failures.add(e.javaClass.simpleName)
                    false
                }
                if (ok) {
                    activeWebSockets.add(b)
                }
                if (ok && winner.complete(Pair(b, c))) {
                    lastRoute = c.kind
                    onLog("[$label] WS connected via ${c.host} (${c.kind})", LogKind.WS)
                } else {
                    if (ok) activeWebSockets.remove(b)
                    try { b.close() } catch (_: Exception) {}
                }
            }
        }

        serverScope.launch {
            jobs.joinAll()
            winner.complete(null)
        }

        var result: Pair<WebSocketBridge, WsCandidate>? = null
        try {
            result = withTimeoutOrNull(timeoutMs) { winner.await() }
        } finally {
            // The arbitration below must also run when serverScope is cancelled under us
            // (stop() / resetConnections): await() then throws CancellationException instead of
            // returning, while connect() is a plain blocking latch that no cancellation can
            // interrupt — so a candidate already dialling still finishes, still wins the
            // deferred, and its bridge would be left with no owner at all (the caller never
            // receives it, and stop() only closes client sockets). NonCancellable ensures this
            // ownership cleanup runs to the end inside an already-cancelled scope.
            withContext(NonCancellable) {
                jobs.forEach { it.cancel() }
                // Slam the door: once the deferred holds null, every candidate still stuck in
                // connect() loses the arbitration and closes the bridge it created itself.
                // If the slam loses, someone won between the await and here — adopt and close
                // that bridge unless it is the one we are handing to a live caller. await()
                // cannot suspend here, the deferred is completed either way by this point.
                if (!winner.complete(null)) {
                    val late = winner.await()?.first
                    if (late != null && late !== result?.first) {
                        activeWebSockets.remove(late)
                        try { late.close() } catch (_: Exception) {}
                    }
                }
            }
        }
        // Note: caching happens in bridgeData once the route actually carries data, not here.
        if (result == null && failures.isNotEmpty()) {
            val summary = failures.groupingBy { it }.eachCount()
                .entries.joinToString(", ") { (klass, n) -> if (n > 1) "$klass ×$n" else klass }
            onLog("[$label] all ${pool.size} endpoint(s) failed: $summary", LogKind.WARNING)
        }
        return result
    }

    private suspend fun bridgeData(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        wsBridge: WebSocketBridge,
        ctx: CryptoContext,
        relayInit: ByteArray,
        protoInt: Long,
        label: String,
        dc: Int,
        isMedia: Boolean,
        candidate: WsCandidate,
        connUp: AtomicLong,
        connDown: AtomicLong,
        closeReason: AtomicReference<String>,
        openedAtEpoch: Int,
    ) {
        // Cannot throw here: relayInit is always 64 bytes and the same AES/CTR cipher was just built
        // for the crypto context. An unknown protocol only makes the splitter pass chunks through.
        val splitter = MsgSplitter(relayInit, protoInt)
        val cacheKey = "$dc/$isMedia"

        // Per-connection traffic lives in the caller's counters: the same numbers feed the
        // stall watchdog below and the CONN summary logged by handleClient when we return.
        // When the last byte moved in each direction. uptimeMillis (not wall clock, not
        // elapsedRealtime) because it is the same monotonic clock delay() runs on and, like
        // delay(), it stops during deep sleep — so a doze period cannot be mistaken for silence.
        //
        // Seeded to session start rather than to a 0 "never" sentinel: 0 is also a legal absolute
        // uptimeMillis() value, so the `now - lastDownAt >= STALL_SILENCE_MS` term below would
        // read "never received" as "silent since device boot" — always past the threshold on a
        // long-up device (the 90s floor silently disappears and eviction needs only the
        // confirmations) and never past it on the BootReceiver auto-start path, where uptime is
        // still under 90s and a genuinely dead route stays hidden. Measuring from session start
        // makes "never" mean "silent for as long as this session has existed", which is the
        // question actually being asked, and both directions then obey STALL_SILENCE_MS.
        val sessionStart = SystemClock.uptimeMillis()
        val lastUpAt = AtomicLong(sessionStart)
        val lastDownAt = AtomicLong(sessionStart)

        // Stall watchdog: a route that opened but never delivers a byte back while the client
        // is actively sending = dead upstream (e.g. a Cloudflare edge that can't reach
        // Telegram). Detect it ourselves, evict the route from cache and drop the client so it
        // reconnects and re-races.
        //
        // Both signals are gated on an empty OkHttp send queue. Upstream bytes are counted at
        // proxy INGRESS — lastUpAt is stamped when they are read off the 256 KB-buffered loopback
        // socket, and wsBridge.send() returns true as soon as OkHttp *enqueues* — so "we sent and
        // nothing came back" (dead route) and "we have not put it on the wire yet" (slow uplink)
        // are otherwise indistinguishable. queueSize() is the discriminator: while it is non-zero
        // the far end cannot possibly have answered, so silence carries no information. This does
        // not blunt the dead-edge catch, because a Cloudflare edge that cannot reach Telegram
        // still drains our frames over its own perfectly healthy TLS socket.
        //
        // Two separate signals, because they need very different evidence:
        //
        //  1. One-shot at STALL_FIRST_BYTE_MS — "we sent, it is all on the wire, and nothing has
        //     EVER come back". That is the case this watchdog was written for, and a route that
        //     delivered even one byte is exempt from it forever, so it cannot misfire mid-session.
        //     A session still draining a backlog at that instant simply falls through to (2).
        //  2. Recurring, for a route that dies later. Its evidence is time-based, never a
        //     per-sample delta of the counters: "downstream unchanged while upstream grew during
        //     this window" is ordinary healthy behaviour — an idle session's
        //     ping_delay_disconnect writes every 30-60s and the pong can easily land on the
        //     other side of a sample boundary, and one upload.saveFilePart on a slow uplink
        //     grows upstream by a whole part before the server can possibly answer. Both look
        //     exactly like a delta-stall. So instead: the trailing STALL_SILENCE_MS window must
        //     hold at least one byte from the client and none back, STALL_CONFIRMATIONS polls in
        //     a row.
        //
        //     The upstream half is a RECENCY bound, not merely "upstream newer than downstream".
        //     The latter is satisfied FOREVER by any session whose last frame happened to go up,
        //     and a msgs_ack — which the server owes no answer to — is exactly how a session
        //     normally falls quiet, so a healthy idle media/download connection reached the
        //     threshold on the clock alone and evicted the proven route for every future
        //     connection to that DC. A client genuinely stuck on a dead route keeps pinging and
        //     resending its pending queries, so it stays inside the window and is still caught;
        //     one that has sent nothing for STALL_SILENCE_MS has no outstanding demand and
        //     nothing user-visible to unstick, so leaving it alone is the answer, not a miss.
        //
        //     Both halves use the one constant on purpose. A wider recency bound W would re-open
        //     the hole: an idle session satisfies the predicate from lastDown + STALL_SILENCE_MS
        //     until lastUp + W — a span of W - STALL_SILENCE_MS + (lastUp - lastDown) — and once
        //     that reaches STALL_POLL_INTERVAL_MS two consecutive confirmations fit inside it.
        //     At W = STALL_SILENCE_MS the span collapses to the gap between the last byte back
        //     and the last byte up, sub-second for an ack (it trails its own container), leaving
        //     an order of magnitude before it could span the 15s a confirmation pair needs.
        //
        //     Detection latency is unchanged by the extra term: silence starts at the last byte
        //     back, the first moment that can see STALL_SILENCE_MS of it is that deadline, and
        //     the confirmation one poll interval after that — about 105s — while a route that
        //     was never alive is still caught at 9s by (1).
        //
        //     An idle session parks on the next client byte and does not arm a timer. The 15s
        //     interval is only the gap between the two confirmations, and the queue check
        //     while an upload is still draining.
        //
        // The bar is deliberately high because tripping is expensive: it evicts the proven route
        // for EVERY session to this DC and kills this client's socket — and since the client then
        // redials and re-sends exactly the same data, a misfire that the data itself provokes
        // reproduces on every redial, i.e. it livelocks instead of recovering.
        val kick = StallKick()
        val watchdog = serverScope.launch {
            try {
                runStallWatch(
                    kick = kick,
                    sessionStart = sessionStart,
                    now = { SystemClock.uptimeMillis() },
                    sample = {
                        StallSample(
                            lastUpAt.get(), lastDownAt.get(),
                            connUp.get(), connDown.get(),
                            wsBridge.queueSize(),
                        )
                    },
                    onTrip = {
                        // No downstream byte at all: this host upgraded and then blackholed.
                        // Remember it, and if it was a direct front, skip the whole direct wave
                        // on the redial. A stall after real bytes only drops the cached route.
                        if (connDown.get() == 0L) {
                            routeGate.markDeadOpen(candidate.host, openedAtEpoch)
                            if (candidate.kind == "direct") {
                                routeGate.recordDirectWaveFailure(openedAtEpoch)
                            }
                        }
                        routeCache.remove(cacheKey)
                        // Set the cause BEFORE closing the sockets: the relay loops fail on the
                        // closed sockets right after, and only the first reason wins.
                        closeReason.compareAndSet("client-gone", "watchdog")
                        onLog("[$label] route stalled (no data back) — dropping & reconnecting", LogKind.WARNING)
                        try { wsBridge.close() } catch (_: Exception) {}
                        try { clientSocket.close() } catch (_: Exception) {}
                    },
                    skipFirstByte = false,
                    firstByteMs = STALL_FIRST_BYTE_MS,
                    silenceMs = STALL_SILENCE_MS,
                    pollMs = STALL_POLL_INTERVAL_MS,
                    confirmationsNeeded = STALL_CONFIRMATIONS,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        val clientToWs = serverScope.async {
            try {
                val buffer = ByteArray(65536)
                // isConnected only means "connect() once succeeded" — it stays true past the
                // peer's disconnect, so it can't guard the loop; !isClosed and the read result can.
                while (running && !clientSocket.isClosed) {
                    val read = clientInput.read(buffer)
                    if (read <= 0) {
                        splitter.flush().forEach { wsBridge.send(it) }
                        // Leave "client-gone". "peer" is reserved for the download loop, where the
                        // client socket is still open. Labeling this EOF as peer made two short
                        // Telegram closes (a small media fetch, an app backgrounding) look like a
                        // dead route and evict a front that had already proven itself.
                        break
                    }
                    connUp.addAndGet(read.toLong())
                    signalTraffic()
                    lastUpAt.set(SystemClock.uptimeMillis())
                    bytesUp.addAndGet(read.toLong())
                    val chunk = buffer.copyOfRange(0, read)
                    val plain = ctx.cltDecryptor.update(chunk)
                    val reenc = ctx.tgEncryptor.update(plain)
                    if (!splitter.split(reenc).all { wsBridge.send(it) }) {
                        closeReason.compareAndSet("client-gone", "peer") // upstream stopped accepting
                        break
                    }
                    kick.arm()
                }
            } catch (e: Exception) {
                // Watchdog/stop() close the sockets under us; only a live socket's failure is a cause.
                if (!clientSocket.isClosed) closeReason.compareAndSet("client-gone", classifyRelayError(e))
            }
        }

        val wsToClient = serverScope.async {
            try {
                while (running && !clientSocket.isClosed) {
                    val data = wsBridge.receive()
                    if (data == null) {
                        // Upstream EOF. Only blame the peer when the client is still with us:
                        // when Telegram hangs up first, select() returns and closes this bridge
                        // under us, so receive() returns null for a close we initiated ourselves.
                        if (!clientSocket.isClosed) closeReason.compareAndSet("client-gone", "peer")
                        break
                    }
                    // The listener already signals at network ingress; repeat here in case the
                    // bounded receive channel made the relay wait through a previous linger.
                    signalTraffic()
                    // First bytes back prove the route carries Telegram traffic → cache it.
                    if (connDown.getAndAdd(data.size.toLong()) == 0L) {
                        routeCache[cacheKey] = candidate
                        routeGate.noteRouteProven(
                            openedAtEpoch,
                            candidate.host,
                            candidate.kind == "direct",
                        )
                    }
                    lastDownAt.set(SystemClock.uptimeMillis())
                    bytesDown.addAndGet(data.size.toLong())
                    val plain = ctx.tgDecryptor.update(data)
                    val encrypted = ctx.cltEncryptor.update(plain)
                    clientOutput.write(encrypted)
                    clientOutput.flush()
                }
            } catch (e: Exception) {
                // Watchdog/stop() close the sockets under us; only a live socket's failure is a cause.
                if (!clientSocket.isClosed) closeReason.compareAndSet("client-gone", classifyRelayError(e))
            }
        }

        try {
            select<Unit> {
                clientToWs.onAwait { }
                wsToClient.onAwait { }
            }
            try { clientSocket.close() } catch (_: Exception) {}
            wsBridge.close()
            activeWebSockets.remove(wsBridge)
            clientToWs.cancel(); wsToClient.cancel()
        } catch (e: CancellationException) {
            // Never swallow cancellation — it is how stop()/resetConnections() tears sessions down.
            throw e
        } catch (_: Exception) {
        } finally {
            watchdog.cancel()
            wsBridge.close()
            activeWebSockets.remove(wsBridge)
            // No per-session log here: handleClient emits the single CONN summary line.
        }
    }

    private suspend fun tcpFallback(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        ctx: CryptoContext,
        relayInit: ByteArray,
        targetIp: String,
        connUp: AtomicLong,
        connDown: AtomicLong,
        closeReason: AtomicReference<String>,
        label: String,
    ): Boolean {
        return try {
            // Socket(host, port) blocks in connect() with no timeout — a blackholed DC IP would
            // pin the coroutine for the full kernel SYN timeout. Bound it explicitly.
            val remoteSocket = Socket().apply { connect(InetSocketAddress(targetIp, 443), UPSTREAM_CONNECT_TIMEOUT_MS) }
            try {
                remoteSocket.tcpNoDelay = true
                remoteSocket.keepAlive = true
                val remoteOutput = remoteSocket.getOutputStream()
                val remoteInput = remoteSocket.getInputStream()

                lastRoute = "tcp"
                remoteOutput.write(relayInit)
                remoteOutput.flush()

                // Same clock rules as the WebSocket watchdog: soTimeout stays 0 so a healthy
                // idle MTProto session is not killed, and a DC that accepted TCP and then
                // blackholed the payload is closed when the client is still asking.
                val sessionStart = SystemClock.uptimeMillis()
                val lastUpAt = AtomicLong(sessionStart)
                val lastDownAt = AtomicLong(sessionStart)
                val kick = StallKick()
                // No 9s one-shot here. Unlike the WebSocket path there is no OkHttp queue to
                // tell "still writing" from "on the wire", and write() itself blocks. The kick
                // is armed only after flush, so a slow write is not mistaken for silence.
                // Until the client sends, the watchdog is parked and holds no timer.
                val watchdog = serverScope.launch {
                    try {
                        runStallWatch(
                            kick = kick,
                            sessionStart = sessionStart,
                            now = { SystemClock.uptimeMillis() },
                            sample = {
                                StallSample(
                                    lastUpAt.get(), lastDownAt.get(),
                                    connUp.get(), connDown.get(),
                                    queueBytes = 0L,
                                )
                            },
                            onTrip = {
                                closeReason.compareAndSet("client-gone", "watchdog")
                                onLog("[$label] TCP route stalled (no data back) — dropping", LogKind.WARNING)
                                try { clientSocket.close() } catch (_: Exception) {}
                                try { remoteSocket.close() } catch (_: Exception) {}
                            },
                            skipFirstByte = true,
                            firstByteMs = STALL_FIRST_BYTE_MS,
                            silenceMs = STALL_SILENCE_MS,
                            pollMs = STALL_POLL_INTERVAL_MS,
                            confirmationsNeeded = STALL_CONFIRMATIONS,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }

                val clientToRemote = serverScope.async {
                    try {
                        val buffer = ByteArray(65536)
                        while (running && !clientSocket.isClosed) {
                            val read = clientInput.read(buffer)
                            if (read <= 0) {
                                // Client hung up. Not an upstream failure — see the WS upload loop.
                                break
                            }
                            connUp.addAndGet(read.toLong())
                            signalTraffic()
                            bytesUp.addAndGet(read.toLong())
                            val chunk = buffer.copyOfRange(0, read)
                            val plain = ctx.cltDecryptor.update(chunk)
                            val reenc = ctx.tgEncryptor.update(plain)
                            remoteOutput.write(reenc)
                            remoteOutput.flush()
                            lastUpAt.set(SystemClock.uptimeMillis())
                            kick.arm()
                        }
                    } catch (e: Exception) {
                        // Sockets closed under us (stop()/watchdog) carry no information.
                        if (!clientSocket.isClosed && !remoteSocket.isClosed)
                            closeReason.compareAndSet("client-gone", classifyRelayError(e))
                    }
                }

                val remoteToClient = serverScope.async {
                    try {
                        val buffer = ByteArray(65536)
                        while (running && !remoteSocket.isClosed) {
                            val read = remoteInput.read(buffer)
                            if (read <= 0) {
                                closeReason.compareAndSet("client-gone", "peer") // upstream EOF
                                break
                            }
                            connDown.addAndGet(read.toLong())
                            signalTraffic()
                            lastDownAt.set(SystemClock.uptimeMillis())
                            bytesDown.addAndGet(read.toLong())
                            val chunk = buffer.copyOfRange(0, read)
                            val plain = ctx.tgDecryptor.update(chunk)
                            val encrypted = ctx.cltEncryptor.update(plain)
                            clientOutput.write(encrypted)
                            clientOutput.flush()
                        }
                    } catch (e: Exception) {
                        // Sockets closed under us (stop()/watchdog) carry no information.
                        if (!clientSocket.isClosed && !remoteSocket.isClosed)
                            closeReason.compareAndSet("client-gone", classifyRelayError(e))
                    }
                }

                try {
                    select<Unit> {
                        clientToRemote.onAwait { }
                        remoteToClient.onAwait { }
                    }
                    try { clientSocket.close() } catch (_: Exception) {}
                    try { remoteSocket.close() } catch (_: Exception) {}
                    clientToRemote.cancel(); remoteToClient.cancel()
                    true
                } finally {
                    watchdog.cancel()
                }
            } finally {
                try { remoteSocket.close() } catch (_: Exception) {}
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation — it is how stop()/resetConnections() tears sessions down.
            throw e
        } catch (e: Exception) {
            onLog("TCP fallback error: ${e.message}", LogKind.ERROR)
            false
        }
    }
    private companion object {
        // Handshake must complete within this window; afterwards reads block (soTimeout = 0).
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        // Whole WS candidate race can't outlast this (in case a bridge.connect() never returns).
        const val WS_RACE_TIMEOUT_MS = 15_000L
        // First-wave (direct endpoints) budget before falling back to the Cloudflare pool. Short
        // so a failing direct route escalates quickly, long enough for a healthy one to win.
        const val WS_WAVE_TIMEOUT_MS = 4_000L
        // Keep route races responsive without opening one TLS socket per candidate at once.
        const val WS_DIAL_CONCURRENCY = 3
        // One-shot "nothing has ever arrived" check. Well past the worst first-response latency
        // (relayInit + a DC round trip is sub-second even on a bad mobile link), short enough
        // that the redial happens while the user is still looking at the app. Kept at 9s: a dead
        // Cloudflare edge drains our frames instantly, so the empty-queue gate it now carries
        // does not delay it — it only spares a session that opens straight into a large upload.
        const val STALL_FIRST_BYTE_MS = 9_000L
        // Poll period of the slow mid-session stall check.
        const val STALL_POLL_INTERVAL_MS = 15_000L
        // How long silence must last before a poll counts as stalled, measured from the later of
        // the last byte back and the last poll that still had bytes queued for the wire. With the
        // uplink accounted for by queueSize() this no longer has to budget for our own send
        // backlog — a pipelined 1.3 MB of 512 KB upload.saveFilePart bodies reaches our loopback
        // buffer in milliseconds and takes >105s to push at 100 kbit/s, which used to be the case
        // this constant was stretched to cover and could not: it is bounded by the uplink, not by
        // any number chosen here. What is left to clear are genuine SERVER-side gaps — a ~0.3s
        // round trip, an idle session's 30-60s ping_delay_disconnect — so 90s keeps well over an
        // order of magnitude of headroom. Doubles as the recency bound on the upstream side of
        // the same check (the window must also CONTAIN a byte from the client); the two must stay
        // one constant — see the watchdog for why splitting them re-opens the idle-session hole.
        const val STALL_SILENCE_MS = 90_000L
        // Consecutive stalled polls required before evicting, so no single unlucky sample — a
        // pong in flight across a poll boundary — can poison the proven route for every session
        // to this DC. 90s + 2 x 15s means a route that dies mid-session is dropped ~2 min in,
        // while the common dead-on-arrival case is already caught at 9s.
        const val STALL_CONFIRMATIONS = 2
        // Connect timeout for raw upstream dials (TCP fallback DC, masking-relay domain).
        // Short: both are best-effort/last-resort paths and a dead target must fail fast.
        const val UPSTREAM_CONNECT_TIMEOUT_MS = 5_000
        // Idle I/O timeout on the benign masking relay so a silent peer can't hang it forever.
        const val RELAY_IO_TIMEOUT_MS = 15_000
        // Avoid wake-lock transition churn on high-throughput sessions; the owner still gets a
        // fresh signal often enough to extend the short active-I/O linger.
        const val TRAFFIC_SIGNAL_MIN_INTERVAL_MS = 2_000L
        // Max accepted Fake-TLS ClientHello record length — bounds the pre-auth allocation.
        const val MAX_CLIENT_HELLO = 4096
        // Max concurrent client connections (loopback proxy; Telegram needs ~15). Flood guard.
        const val MAX_CLIENTS = 128
        // Caesar shift for the bundled CF domain labels. Deliberately weak: it only keeps the
        // domains out of bulk APK string scrapes (issue #39), it is not a secrecy mechanism.
        const val CF_SHIFT = 7

        /** Decode a hex MTProto secret, failing loudly on odd length / non-hex instead of crashing. */
        fun parseSecret(s: String): ByteArray {
            val hex = s.trim()
            require(hex.isNotEmpty() && hex.length % 2 == 0 &&
                hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "Invalid secret: expected an even-length hex string"
            }
            // 32 hex chars = 16-byte base secret; 34 = 17 bytes with a dd/ee prefix byte.
            // Anything else decodes to a key of the wrong size and fails much later and
            // much more obscurely (HMAC/AES with a bad key length), so reject it here.
            require(hex.length == 32 || hex.length == 34) {
                "Invalid secret: expected 32 or 34 hex characters, got ${hex.length}"
            }
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

}
