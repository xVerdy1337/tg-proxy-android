package com.tgwsproxy.proxy

import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class WebSocketBridge {

    // Base client with standard TLS validation — Telegram servers have valid certificates.
    // DNS override is applied per-connect so each call can target a different IP.
    //
    // pingInterval is the critical bit for reliability: OkHttp sends a WebSocket ping every
    // 30s, which (a) keeps the tunnel's NAT mapping alive on mobile/Wi-Fi so the carrier
    // doesn't silently reap an idle connection, and (b) makes OkHttp *ignore* the socket read
    // timeout for an established socket and instead detect death via missing pongs. Without
    // it, an idle chat (>readTimeout) had its WebSocket torn down — Telegram then showed
    // "connecting" and only redialed once the app was reopened. We keep readTimeout only for
    // the initial connect/handshake. 30s is a battery/reliability balance: most carrier/Wi-Fi
    // NATs hold an idle mapping 60-120s, so 30s keeps it alive with fewer radio wakeups than 20s.
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    // Bounded buffer (not Channel.BUFFERED's tiny ~64). On overflow we drop the socket
    // instead of silently losing MTProto frames — a corrupted stream is worse than a redial.
    private val receiveChannel = Channel<ByteArray>(capacity = 1024)
    @Volatile private var isConnected = false
    @Volatile private var isClosed = false

    /**
     * Connect the WebSocket to Telegram's /apiws endpoint.
     *
     * @param targetIp When non-null, the [domain] is pinned to this exact IP via a DNS
     *   override (used for the direct web-front connection where we hardcode the DC IP).
     *   When null, the domain is resolved through the system resolver — this is what the
     *   Cloudflare-proxy fallback needs, since the CF domains must resolve to Cloudflare's
     *   own anycast IPs (which censors can't easily block) rather than a raw Telegram IP.
     */
    fun connect(targetIp: String?, domain: String, path: String = "/apiws"): Boolean {
        if (isConnected) return true

        // Use domain in URL → correct TLS SNI. With a pinned targetIp we override DNS so the
        // domain routes to the specific DC IP without a real lookup; otherwise fall back to
        // the system resolver (Cloudflare-fronted domains).
        val builder = baseClient.newBuilder()
        if (targetIp != null) {
            builder.dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    return listOf(java.net.InetAddress.getByName(targetIp))
                }
            })
        }
        val client = builder.build()

        val request = Request.Builder()
            .url("wss://$domain$path")
            // Telegram's /apiws endpoint requires the "binary" WebSocket subprotocol.
            .addHeader("Sec-WebSocket-Protocol", "binary")
            .addHeader("Origin", "https://$domain")
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
                // If downstream (wsToClient) falls behind and the bounded channel fills up,
                // tear the socket down rather than dropping frames on the floor.
                if (!receiveChannel.trySend(bytes.toByteArray()).isSuccess) {
                    ws.cancel()
                    receiveChannel.close()
                }
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

        latch.await(5, TimeUnit.SECONDS)
        return connected
    }

    fun send(data: ByteArray): Boolean {
        // data.toByteString(0, size) avoids the array copy that the *data spread operator
        // forces on every frame (okio moved the 3-arg of() to this extension). send() is hot.
        return webSocket?.send(data.toByteString(0, data.size)) ?: false
    }

    suspend fun receive(): ByteArray? {
        // receiveCatching returns a closed result (→ null) once the channel is closed,
        // so there's no need to probe isEmpty (not a reliable readiness signal).
        return receiveChannel.receiveCatching().getOrNull()
    }

    fun close() {
        isClosed = true
        webSocket?.close(1000, "Closing")
        receiveChannel.close()
    }
}
