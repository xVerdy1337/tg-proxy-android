package com.tgwsproxy.proxy

import kotlinx.coroutines.*
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MtProtoProxyServer(
    private val host: String,
    private val port: Int,
    private val secret: String,
    private val onLog: (String) -> Unit,
    private val onConnectionChange: (Int) -> Unit,
    // Optional user-supplied Cloudflare-proxy base domain (e.g. "mydomain.com").
    // When set it is tried FIRST in the CF fallback, before the bundled defaults.
    private val cfDomain: String = ""
) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val activeConnections = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionCount = AtomicInteger(0)
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val secretBytes = secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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
    // that block Telegram's IP ranges directly (DPI / TSPU) — both the direct WS and the
    // TCP fallback target Telegram IPs and die there; only the CF path stays reachable.
    // Source/idea: Flowseal/tg-ws-proxy + Nekogram/WSProxy.
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
        activeConnections.forEach { try { it.close() } catch (_: Exception) {} }
        activeConnections.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        onLog("Прокси остановлен")
    }

    private suspend fun handleClient(clientSocket: Socket) {
        val label = clientSocket.inetAddress?.hostAddress ?: "?"
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.receiveBufferSize = 256 * 1024
            clientSocket.sendBufferSize = 256 * 1024

            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            // Read handshake
            val handshake = ByteArray(MtProtoConstants.HANDSHAKE_LEN)
            var read = 0
            while (read < MtProtoConstants.HANDSHAKE_LEN) {
                val n = input.read(handshake, read, MtProtoConstants.HANDSHAKE_LEN - read)
                if (n <= 0) {
                    onLog("[$label] disconnected before handshake")
                    return
                }
                read += n
            }

            // Try handshake
            val result = MtProtoHandshake.tryHandshake(handshake, secretBytes)
            if (result == null) {
                onLog("[$label] bad handshake (wrong secret or proto)")
                // Drain and close
                try { input.skip(Long.MAX_VALUE) } catch (_: Exception) {}
                return
            }

            val protoInt = when {
                result.protoTag.contentEquals(MtProtoConstants.PROTO_TAG_ABRIDGED) -> MtProtoConstants.PROTO_ABRIDGED_INT
                result.protoTag.contentEquals(MtProtoConstants.PROTO_TAG_INTERMEDIATE) -> MtProtoConstants.PROTO_INTERMEDIATE_INT
                else -> MtProtoConstants.PROTO_PADDED_INTERMEDIATE_INT
            }

            val dcIdx = if (result.isMedia) -result.dcId else result.dcId
            onLog("[$label] handshake ok: DC${result.dcId}${if (result.isMedia) " media" else ""} proto=0x${protoInt.toString(16)}")

            // Generate relay init and crypto context.
            // NOTE: relayInit is the obfuscation header for the TELEGRAM side only — it must
            // be sent to Telegram (first WS frame / first TCP bytes), NEVER back to the client.
            val relayInit = MtProtoHandshake.generateRelayInit(result.protoTag, dcIdx)

            val cryptoCtx = MtProtoHandshake.buildCryptoContext(
                result.clientDecPrekeyIv,
                secretBytes,
                relayInit
            )

            // Connect WebSocket. A WebSocketBridge can only be used for ONE connection
            // attempt (its receive channel is closed on failure), so every attempt gets a
            // fresh instance. tryConnect returns a connected bridge or null.
            //
            // 1) Direct: web-front IPs that serve /apiws (SNI = kwsN.web.telegram.org).
            val domains = MtProtoHandshake.wsDomains(result.dcId, result.isMedia)
            val wsTargetIp = wsFrontIps[result.dcId] ?: wsFrontIps[2]!!
            var wsBridge: WebSocketBridge? = null

            for (domain in domains) {
                onLog("[$label] connecting WS to $domain via $wsTargetIp")
                val b = WebSocketBridge()
                if (b.connect(wsTargetIp, domain)) {
                    wsBridge = b
                    onLog("[$label] WS connected to $domain")
                    break
                }
            }

            // 2) Cloudflare proxy: if the direct web-front WS failed (most likely the network
            // blocks Telegram IPs), connect to kwsN.<cf-domain>. That host resolves to
            // Cloudflare anycast IPs and is bridged to /apiws server-side — same crypto and
            // framing, just a transport host that survives IP-based blocking (DPI / TSPU).
            if (wsBridge == null) {
                onLog("[$label] direct WS failed, trying Cloudflare proxy")
                val cfDc = if (result.dcId == 203) 2 else result.dcId
                for (base in cfDomains) {
                    val cfHost = "kws$cfDc.$base"
                    onLog("[$label] connecting WS via Cloudflare $cfHost")
                    val b = WebSocketBridge()
                    // targetIp = null → resolve through the system DNS to Cloudflare IPs.
                    if (b.connect(null, cfHost)) {
                        wsBridge = b
                        onLog("[$label] WS connected via Cloudflare $cfHost")
                        break
                    }
                }
            }

            if (wsBridge == null) {
                onLog("[$label] WS connection failed, trying TCP fallback")
                // TCP fallback to the raw MTProto core IP.
                val fallbackIp = dcDefaultIps[result.dcId] ?: dcDefaultIps[2]!!
                val fallbackOk = tcpFallback(clientSocket, input, output, cryptoCtx, relayInit, fallbackIp)
                if (!fallbackOk) {
                    onLog("[$label] TCP fallback failed")
                }
                return
            }

            val bridge = wsBridge

            // Hand Telegram the relay obfuscation init as the very first WS frame.
            bridge.send(relayInit)

            // Bridge data with full re-encryption + per-packet WS framing.
            bridgeData(clientSocket, input, output, bridge, cryptoCtx, relayInit, protoInt, label, result.dcId, result.isMedia)

        } catch (e: Exception) {
            onLog("[$label] error: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            activeConnections.remove(clientSocket)
            val count = connectionCount.decrementAndGet()
            onConnectionChange(count)
        }
    }

    private suspend fun bridgeData(
        clientSocket: Socket,
        clientInput: java.io.InputStream,
        clientOutput: java.io.OutputStream,
        wsBridge: WebSocketBridge,
        ctx: CryptoContext,
        relayInit: ByteArray,
        protoInt: Long,
        label: String,
        dc: Int,
        isMedia: Boolean
    ) {
        val splitter = try { MsgSplitter(relayInit, protoInt) } catch (_: Exception) { null }

        // client (TCP) -> telegram (WS): decrypt client obfuscation, re-encrypt with relay
        // obfuscation, split into individual MTProto packets, one per WS frame.
        val clientToWs = serverScope.async {
            try {
                val buffer = ByteArray(65536)
                while (running && clientSocket.isConnected && !clientSocket.isClosed) {
                    val read = clientInput.read(buffer)
                    if (read <= 0) {
                        splitter?.flush()?.forEach { wsBridge.send(it) }
                        break
                    }
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

        // telegram (WS) -> client (TCP): decrypt relay obfuscation, re-encrypt with client
        // obfuscation.
        val wsToClient = serverScope.async {
            try {
                while (running && clientSocket.isConnected && !clientSocket.isClosed) {
                    val data = wsBridge.receive() ?: break
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
        clientInput: java.io.InputStream,
        clientOutput: java.io.OutputStream,
        ctx: CryptoContext,
        relayInit: ByteArray,
        targetIp: String
    ): Boolean {
        return try {
            val remoteSocket = Socket(targetIp, 443)
            remoteSocket.tcpNoDelay = true
            val remoteOutput = remoteSocket.getOutputStream()
            val remoteInput = remoteSocket.getInputStream()

            // Raw obfuscated2 transport also needs the relay init prefix first.
            remoteOutput.write(relayInit)
            remoteOutput.flush()

            val clientToRemote = serverScope.async {
                try {
                    val buffer = ByteArray(65536)
                    while (running && clientSocket.isConnected && !clientSocket.isClosed) {
                        val read = clientInput.read(buffer)
                        if (read <= 0) break
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
