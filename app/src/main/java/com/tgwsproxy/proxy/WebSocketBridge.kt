package com.tgwsproxy.proxy

import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class WebSocketBridge {

    // Base client with standard TLS validation — Telegram servers have valid certificates.
    // DNS override is applied per-connect so each call can target a different IP.
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val receiveChannel = Channel<ByteArray>(Channel.BUFFERED)
    private var isConnected = false
    private var isClosed = false

    fun connect(targetIp: String, domain: String, path: String = "/apiws"): Boolean {
        if (isConnected) return true

        // Use domain in URL → correct TLS SNI (*.web.telegram.org)
        // DNS override routes that domain to the specific DC IP without a real DNS lookup.
        val client = baseClient.newBuilder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    return listOf(java.net.InetAddress.getByName(targetIp))
                }
            })
            .build()

        val request = Request.Builder()
            .url("wss://$domain$path")
            .build()

        val latch = java.util.concurrent.CountDownLatch(1)
        var connected = false

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
                isConnected = false
                isClosed = true
                receiveChannel.close()
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
}
