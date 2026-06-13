package com.tgwsproxy.proxy

import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MtProtoProxyServer(
    private val host: String,
    private val port: Int,
    private val secret: String,
    private val onLog: (String) -> Unit,
    private val onConnectionChange: (Int) -> Unit,
    // Optional user-supplied Cloudflare-proxy base domain (e.g. "mydomain.com").
    // When set it is tried FIRST in the CF fallback, before the bundled defaults.
    private val cfDomain: String = "",
    // Optional Fake-TLS masking domain. When non-empty, clients connect with an `ee...`
    // secret and wrap the obfuscated2 stream in TLS records that look like HTTPS to [this
    // domain] — the strongest DPI bypass. Empty = plain (dd) handshake on the raw path.
    private val fakeTlsDomain: String = ""
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val activeConnections = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionCount = AtomicInteger(0)
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val secretBytes = secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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
    private val defaultCfDomains = listOf(
        "noskomnadzor.co.uk",
        "kartoshka.co.uk",
        "cakeisalie.co.uk",
        "lovetrue.co.uk",
        "sorokdva.co.uk",
        "havegreatday.co.uk",
        "pomogite.co.uk",
        "pclead.co.uk",
        "offshor.co.uk"
    )

    private val cfDomains: List<String> by lazy {
        val user = cfDomain.trim()
        if (user.isNotEmpty()) listOf(user) + defaultCfDomains else defaultCfDomains
    }

    fun start() {
        running = true
        serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName(host))
        serverSocket?.receiveBufferSize = 256 * 1024
        onLog("Прокси слушает на $host:$port")

        while (running) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                if (!running) {
                    clientSocket.close()
                    break
                }
                activeConnections.add(clientSocket)
                val count = connectionCount.incrementAndGet()
                onConnectionChange(count)

                serverScope.launch {
                    handleClient(clientSocket)
                }
            } catch (e: SocketException) {
                if (running) {
                    onLog("Socket error: ${e.message}")
                }
                break
            } catch (e: IOException) {
                onLog("IO error: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        serverScope.cancel()
        routeCache.clear()
        activeConnections.forEach { try { it.close() } catch (_: Exception) {} }
        activeConnections.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        onLog("Прокси остановлен")
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
        // blocked on mobile (or vice-versa), so drop the cache and let the next connect race.
        routeCache.clear()
        if (n > 0) onLog("Сеть изменилась — переподключаю ($n)")
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
        val label = clientSocket.inetAddress?.hostAddress ?: "?"
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.keepAlive = true
            clientSocket.receiveBufferSize = 256 * 1024
            clientSocket.sendBufferSize = 256 * 1024

            val rawInput = clientSocket.getInputStream()
            val rawOutput = clientSocket.getOutputStream()

            // Streams the rest of the pipeline reads/writes — possibly wrapped in Fake TLS.
            var clientInput: InputStream = rawInput
            var clientOutput: OutputStream = rawOutput

            // Read the first byte to tell a TLS ClientHello (Fake TLS) from a raw obfs2 init.
            val firstByte = readFully(rawInput, 1)
            if (firstByte == null) {
                onLog("[$label] disconnected before handshake")
                return
            }

            val handshake: ByteArray
            if (fakeTlsDomain.isNotEmpty() && (firstByte[0].toInt() and 0xFF) == FakeTls.TLS_RECORD_HANDSHAKE) {
                // --- Fake TLS path ---
                val hdrRest = readFully(rawInput, 4)
                if (hdrRest == null) { onLog("[$label] incomplete TLS header"); return }
                val recLen = ((hdrRest[2].toInt() and 0xFF) shl 8) or (hdrRest[3].toInt() and 0xFF)
                val body = readFully(rawInput, recLen)
                if (body == null) { onLog("[$label] incomplete TLS body"); return }
                val clientHello = firstByte + hdrRest + body

                val verified = FakeTls.verifyClientHello(clientHello, secretBytes)
                if (verified == null) {
                    // Probe / wrong secret — relay to the real masking domain so we look benign.
                    onLog("[$label] Fake TLS verify failed → masking")
                    maskingRelay(clientSocket, rawInput, rawOutput, clientHello)
                    return
                }

                val serverHello = FakeTls.buildServerHello(secretBytes, verified.clientRandom, verified.sessionId)
                rawOutput.write(serverHello)
                rawOutput.flush()

                clientInput = FakeTlsInputStream(rawInput)
                clientOutput = FakeTlsOutputStream(rawOutput)

                val inner = readFully(clientInput, MtProtoConstants.HANDSHAKE_LEN)
                if (inner == null) { onLog("[$label] incomplete obfs2 init inside TLS"); return }
                handshake = inner
                onLog("[$label] Fake TLS handshake ok")
            } else {
                // --- Raw obfuscated2 path: first byte + remaining 63. ---
                val rest = readFully(rawInput, MtProtoConstants.HANDSHAKE_LEN - 1)
                if (rest == null) { onLog("[$label] disconnected before handshake"); return }
                handshake = firstByte + rest
            }

            // Try handshake
            val result = MtProtoHandshake.tryHandshake(handshake, secretBytes)
            if (result == null) {
                onLog("[$label] bad handshake (wrong secret or proto)")
                try { clientInput.skip(Long.MAX_VALUE) } catch (_: Exception) {}
                return
            }

            val protoInt = when {
                result.protoTag.contentEquals(MtProtoConstants.PROTO_TAG_ABRIDGED) -> MtProtoConstants.PROTO_ABRIDGED_INT
                result.protoTag.contentEquals(MtProtoConstants.PROTO_TAG_INTERMEDIATE) -> MtProtoConstants.PROTO_INTERMEDIATE_INT
                else -> MtProtoConstants.PROTO_PADDED_INTERMEDIATE_INT
            }

            val dcIdx = if (result.isMedia) -result.dcId else result.dcId
            onLog("[$label] handshake ok: DC${result.dcId}${if (result.isMedia) " media" else ""} proto=0x${protoInt.toString(16)}")

            // relayInit is the TELEGRAM-side obfuscation header — sent to Telegram only.
            val relayInit = MtProtoHandshake.generateRelayInit(result.protoTag, dcIdx)

            val cryptoCtx = MtProtoHandshake.buildCryptoContext(
                result.clientDecPrekeyIv,
                secretBytes,
                relayInit
            )

            // Connect WebSocket by racing every transport candidate; first to open wins.
            val wsBridge = connectAnyWs(result.dcId, result.isMedia, label)

            if (wsBridge == null) {
                onLog("[$label] WS connection failed, trying TCP fallback")
                val fallbackIp = dcDefaultIps[result.dcId] ?: dcDefaultIps[2]!!
                val fallbackOk = tcpFallback(clientSocket, clientInput, clientOutput, cryptoCtx, relayInit, fallbackIp)
                if (!fallbackOk) {
                    onLog("[$label] TCP fallback failed")
                }
                return
            }

            val bridge = wsBridge

            // Hand Telegram the relay obfuscation init as the very first WS frame.
            bridge.send(relayInit)

            bridgeData(clientSocket, clientInput, clientOutput, bridge, cryptoCtx, relayInit, protoInt, label, result.dcId, result.isMedia)

        } catch (e: Exception) {
            onLog("[$label] error: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            activeConnections.remove(clientSocket)
            val count = connectionCount.decrementAndGet()
            onConnectionChange(count)
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
        initialData: ByteArray
    ) {
        try {
            val up = Socket(fakeTlsDomain, 443)
            up.tcpNoDelay = true
            val upOut = up.getOutputStream()
            val upIn = up.getInputStream()
            upOut.write(initialData); upOut.flush()

            val a = serverScope.async {
                try {
                    val b = ByteArray(16384)
                    while (!clientSocket.isClosed) {
                        val r = clientInput.read(b); if (r <= 0) break
                        upOut.write(b, 0, r); upOut.flush()
                    }
                } catch (_: Exception) {}
            }
            val c = serverScope.async {
                try {
                    val b = ByteArray(16384)
                    while (!up.isClosed) {
                        val r = upIn.read(b); if (r <= 0) break
                        clientOutput.write(b, 0, r); clientOutput.flush()
                    }
                } catch (_: Exception) {}
            }
            awaitAll(a, c)
            try { up.close() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private data class WsCandidate(val pinnedIp: String?, val host: String, val kind: String)

    // Remembers the last endpoint that worked, keyed by "dcId/isMedia". On a reconnect we try
    // it alone first instead of racing ~18 TLS handshakes again — big battery/radio saver,
    // especially when a network switch forces every session to redial at once. Falls back to
    // the full race if the cached endpoint no longer answers.
    private val routeCache = ConcurrentHashMap<String, WsCandidate>()

    private suspend fun connectAnyWs(dcId: Int, isMedia: Boolean, label: String): WebSocketBridge? {
        val cacheKey = "$dcId/$isMedia"

        routeCache[cacheKey]?.let { cached ->
            val b = WebSocketBridge()
            val ok = try { b.connect(cached.pinnedIp, cached.host) } catch (_: Exception) { false }
            if (ok) {
                lastRoute = cached.kind
                onLog("[$label] WS reconnected via ${cached.host} (${cached.kind}, cached)")
                return b
            }
            try { b.close() } catch (_: Exception) {}
            routeCache.remove(cacheKey) // cached endpoint went stale — fall back to full race
        }

        val candidates = ArrayList<WsCandidate>()

        val wsTargetIp = wsFrontIps[dcId] ?: wsFrontIps[2]!!
        for (domain in MtProtoHandshake.wsDomains(dcId, isMedia)) {
            candidates.add(WsCandidate(wsTargetIp, domain, "direct"))
        }

        val cfDc = if (dcId == 203) 2 else dcId
        for (base in cfDomains) {
            candidates.add(WsCandidate(null, "kws$cfDc.$base", "cloudflare"))
        }

        onLog("[$label] connecting WS: racing ${candidates.size} endpoints (direct + Cloudflare)")

        val winner = kotlinx.coroutines.CompletableDeferred<WebSocketBridge?>()
        val bridges = java.util.Collections.synchronizedList(ArrayList<WebSocketBridge>())
        val winningCandidate = java.util.concurrent.atomic.AtomicReference<WsCandidate?>(null)

        val jobs = candidates.map { c ->
            serverScope.launch {
                val b = WebSocketBridge()
                bridges.add(b)
                val ok = try { b.connect(c.pinnedIp, c.host) } catch (_: Exception) { false }
                if (ok && !winner.isCompleted && winner.complete(b)) {
                    winningCandidate.set(c)
                    lastRoute = c.kind
                    onLog("[$label] WS connected via ${c.host} (${c.kind})")
                } else {
                    try { b.close() } catch (_: Exception) {}
                }
            }
        }

        serverScope.launch {
            jobs.joinAll()
            winner.complete(null)
        }

        val bridge = winner.await()
        jobs.forEach { it.cancel() }
        synchronized(bridges) {
            for (b in bridges) if (b !== bridge) try { b.close() } catch (_: Exception) {}
        }
        if (bridge != null) {
            winningCandidate.get()?.let { routeCache[cacheKey] = it }
        } else {
            onLog("[$label] all WS endpoints failed")
        }
        return bridge
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
        isMedia: Boolean
    ) {
        val splitter = try { MsgSplitter(relayInit, protoInt) } catch (_: Exception) { null }

        val clientToWs = serverScope.async {
            try {
                val buffer = ByteArray(65536)
                while (running && clientSocket.isConnected && !clientSocket.isClosed) {
                    val read = clientInput.read(buffer)
                    if (read <= 0) {
                        splitter?.flush()?.forEach { wsBridge.send(it) }
                        break
                    }
                    bytesUp.addAndGet(read.toLong())
                    val chunk = buffer.copyOfRange(0, read)
                    val plain = ctx.cltDecryptor.update(chunk)
                    val reenc = ctx.tgEncryptor.update(plain)
                    if (splitter != null) {
                        val parts = splitter.split(reenc)
                        var ok = true
                        for (p in parts) {
                            if (!wsBridge.send(p)) { ok = false; break }
                        }
                        if (!ok) break
                    } else {
                        if (!wsBridge.send(reenc)) break
                    }
                }
            } catch (_: Exception) {
            }
        }

        val wsToClient = serverScope.async {
            try {
                while (running && clientSocket.isConnected && !clientSocket.isClosed) {
                    val data = wsBridge.receive() ?: break
                    bytesDown.addAndGet(data.size.toLong())
                    val plain = ctx.tgDecryptor.update(data)
                    val encrypted = ctx.cltEncryptor.update(plain)
                    clientOutput.write(encrypted)
                    clientOutput.flush()
                }
            } catch (_: Exception) {
            }
        }

        try {
            awaitAll(clientToWs, wsToClient)
        } catch (_: Exception) {
        } finally {
            wsBridge.close()
            onLog("[$label] DC${dc}${if (isMedia) "m" else ""} session closed")
        }
    }

    private suspend fun tcpFallback(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        ctx: CryptoContext,
        relayInit: ByteArray,
        targetIp: String
    ): Boolean {
        return try {
            val remoteSocket = Socket(targetIp, 443)
            remoteSocket.tcpNoDelay = true
            remoteSocket.keepAlive = true
            val remoteOutput = remoteSocket.getOutputStream()
            val remoteInput = remoteSocket.getInputStream()

            lastRoute = "tcp"
            remoteOutput.write(relayInit)
            remoteOutput.flush()

            val clientToRemote = serverScope.async {
                try {
                    val buffer = ByteArray(65536)
                    while (running && clientSocket.isConnected && !clientSocket.isClosed) {
                        val read = clientInput.read(buffer)
                        if (read <= 0) break
                        bytesUp.addAndGet(read.toLong())
                        val chunk = buffer.copyOfRange(0, read)
                        val plain = ctx.cltDecryptor.update(chunk)
                        val reenc = ctx.tgEncryptor.update(plain)
                        remoteOutput.write(reenc)
                        remoteOutput.flush()
                    }
                } catch (_: Exception) {}
            }

            val remoteToClient = serverScope.async {
                try {
                    val buffer = ByteArray(65536)
                    while (running && remoteSocket.isConnected && !remoteSocket.isClosed) {
                        val read = remoteInput.read(buffer)
                        if (read <= 0) break
                        bytesDown.addAndGet(read.toLong())
                        val chunk = buffer.copyOfRange(0, read)
                        val plain = ctx.tgDecryptor.update(chunk)
                        val encrypted = ctx.cltEncryptor.update(plain)
                        clientOutput.write(encrypted)
                        clientOutput.flush()
                    }
                } catch (_: Exception) {}
            }

            awaitAll(clientToRemote, remoteToClient)
            try { remoteSocket.close() } catch (_: Exception) {}
            true
        } catch (e: Exception) {
            onLog("TCP fallback error: ${e.message}")
            false
        }
    }
}
