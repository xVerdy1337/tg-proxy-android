package com.tgwsproxy.proxy

import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebSocketBridge {

    private val client: OkHttpClient
    private var webSocket: WebSocket? = null
    private val receiveChannel = Channel<ByteArray>(Channel.BUFFERED)
    private var isConnected = false
    private var isClosed = false

    init {
        // Create trust-all manager for Telegram's self-signed/edge certificates
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }

        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun connect(targetIp: String, domain: String, path: String = "/apiws"): Boolean {
        if (isConnected) return true

        val request = Request.Builder()
            .url("wss://$targetIp$path")
            .header("Host", domain)
            .header("Upgrade", "websocket")
            .header("Connection", "Upgrade")
            .header("Sec-WebSocket-Key", generateKey())
            .header("Sec-WebSocket-Version", "13")
            .header("Sec-WebSocket-Protocol", "binary")
            .build()

        val latch = java.util.concurrent.CountDownLatch(1)
        var connected = false
        var error: Throwable? = null

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                isConnected = true
                latch.countDown()
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                receiveChannel.trySend(bytes.toByteArray())
            }

            override fun onMessage(ws: WebSocket, text: String) {}

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                isClosed = true
                receiveChannel.close()
                latch.countDown()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                error = t
                isConnected = false
                latch.countDown()
            }
        })

        latch.await(10, TimeUnit.SECONDS)
        return connected
    }

    fun send(data: ByteArray): Boolean {
        return webSocket?.send(ByteString.of(*data)) ?: false
    }

    suspend fun receive(): ByteArray? {
        if (isClosed && receiveChannel.isEmpty) return null
        return receiveChannel.receiveCatching().getOrNull()
    }

    fun close() {
        isClosed = true
        webSocket?.close(1000, "Closing")
        receiveChannel.close()
    }

    private fun generateKey(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
