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
    private val onConnectionChange: (Int) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val activeConnections = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionCount = AtomicInteger(0)
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val secretBytes = secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Default DC IPs (fallback)
    private val dcDefaultIps = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100"
    )

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

            // Generate relay init and crypto context
            val relayInit = MtProtoHandshake.generateRelayInit(result.protoTag, dcIdx)
            val cryptoCtx = MtProtoHandshake.buildCryptoContext(
                result.clientDecPrekeyIv,
                secretBytes,
                relayInit
            )

            // Connect WebSocket
            val domains = MtProtoHandshake.wsDomains(result.dcId, result.isMedia)
            val targetIp = dcDefaultIps[result.dcId] ?: dcDefaultIps[2]!!
            val wsBridge = WebSocketBridge()
            var connected = false

            for (domain in domains) {
                onLog("[$label] connecting WS to $domain via $targetIp")
                if (wsBridge.connect(targetIp, domain)) {
                    connected = true
                    onLog("[$label] WS connected to $domain")
                    break
                }
            }

            if (!connected) {
                onLog("[$label] WS connection failed, trying TCP fallback")
                // TCP fallback
                val fallbackOk = tcpFallback(clientSocket, input, output, relayInit, cryptoCtx, targetIp)
                if (!fallbackOk) {
                    onLog("[$label] TCP fallback failed")
                }
                return
            }

            // Send relay init through WS
            wsBridge.send(relayInit)

            // Bridge data
            bridgeData(clientSocket, input, output, wsBridge, cryptoCtx, label, result.dcId, result.isMedia)

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
        label: String,
        dc: Int,
        isMedia: Boolean
    ) {
        val clientToWs = serverScope.async {
            try {
                val buffer = ByteArray(65536)
                while (running && clientSocket.isConnected && !clientSocket.isClosed) {
                    val read = clientInput.read(buffer)
                    if (read <= 0) break
                    val chunk = buffer.copyOfRange(0, read)
                    val plain = ctx.cltDecryptor.update(chunk)
                    val encrypted = ctx.tgEncryptor.update(plain)
                    if (!wsBridge.send(encrypted)) break
                }
            } catch (_: Exception) {
            }
        }

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
        relayInit: ByteArray,
        ctx: CryptoContext,
        targetIp: String
    ): Boolean {
        return try {
            val remoteSocket = Socket(targetIp, 443)
            remoteSocket.tcpNoDelay = true
            val remoteOutput = remoteSocket.getOutputStream()
            val remoteInput = remoteSocket.getInputStream()

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
                        val encrypted = ctx.tgEncryptor.update(plain)
                        remoteOutput.write(encrypted)
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
