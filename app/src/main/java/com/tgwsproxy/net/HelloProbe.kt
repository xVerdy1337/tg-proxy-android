package com.tgwsproxy.net

import com.tgwsproxy.desync.DesyncEngine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Standalone connectivity / DPI-method probe.
 *
 * It opens a *direct* TCP connection to a target host:443 (independent of the VPN), sends a real
 * TLS ClientHello carrying the host as SNI — optionally re-framed by [DesyncEngine] — and watches
 * what happens:
 *
 *  - the server replies with a real TLS handshake record (ServerHello) -> [Outcome.PASS]
 *  - the provider DPI (TSPU) resets the flow, the server sends a TLS alert, the connection is
 *    silently dropped/throttled, or a non-TLS interceptor answers -> [Outcome.BLOCKED]
 *  - DNS / routing failures -> [Outcome.ERROR]
 *
 * Comparing the PLAIN result (no desync, usually BLOCKED) against TLSREC / SPLIT (hopefully PASS)
 * tells us directly whether a method defeats the DPI on the user's network — without depending on
 * the userspace VPN data path at all.
 */
object HelloProbe {

    enum class Method { PLAIN, TLSREC, SPLIT }
    enum class Outcome { PASS, BLOCKED, ERROR }

    data class Result(
        val method: Method,
        val outcome: Outcome,
        val latencyMs: Long,
        val detail: String,
    )

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 3500

    /**
     * Run one probe: connect, send the (maybe re-framed) ClientHello, classify the reaction.
     *
     * PASS is granted ONLY when the server replies with a real TLS handshake record (first byte
     * 0x16 = ServerHello). Everything else is treated conservatively so we never report "работает"
     * when it doesn't:
     *   - RST / broken pipe                      -> BLOCKED (DPI сбросил поток)
     *   - TLS alert (0x15)                       -> BLOCKED (TLS-уровневый отказ, часто по SNI)
     *   - silence until timeout (drop/throttle)  -> BLOCKED (нет ответа)
     *   - clean FIN/EOF with no data             -> BLOCKED (закрыто без handshake)
     *   - any non-TLS byte (interceptor/stub)    -> BLOCKED (подозрительный ответ)
     *   - DNS / route / connect failure          -> ERROR
     *
     * latencyMs is the true response time (last byte sent -> first reaction), measured with
     * nanoTime; for the timeout case it is just the read window and must not be read as RTT.
     */
    fun probe(host: String, port: Int = 443, method: Method): Result {
        val startNs = System.nanoTime()
        var sentNs = startNs
        var sock: Socket? = null
        try {
            val addr = InetAddress.getByName(host)            // direct DNS
            sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS)

            val hello = buildClientHello(host)
            val out = sock.getOutputStream()
            when (method) {
                Method.PLAIN -> {
                    out.write(hello); out.flush()
                }
                Method.TLSREC, Method.SPLIT -> {
                    val m = if (method == Method.TLSREC) DesyncEngine.Method.TLSREC else DesyncEngine.Method.SPLIT
                    val plan = DesyncEngine.plan(hello, m)
                    for ((i, chunk) in plan.chunks.withIndex()) {
                        out.write(chunk); out.flush()
                        // Keep the segments apart so the SNI is genuinely split on the wire, but
                        // do it BEFORE we start the RTT clock so it never pollutes latencyMs.
                        if (i < plan.chunks.size - 1) try { Thread.sleep(8) } catch (_: InterruptedException) {}
                    }
                }
            }

            // Start the RTT clock now: everything after this is the server's reaction time.
            sentNs = System.nanoTime()
            sock.soTimeout = READ_TIMEOUT_MS
            val ins = sock.getInputStream()
            val first = try {
                ins.read()
            } catch (e: SocketTimeoutException) {
                // A live HTTPS server replies within ~RTT on a direct connection. Silence here means
                // the flow was silently dropped/throttled — that is NOT a working bypass.
                return Result(method, Outcome.BLOCKED, elapsedMs(sentNs), "нет ответа (тихий дроп/троттл)")
            }
            val rttMs = elapsedMs(sentNs)
            return when {
                first < 0 ->
                    Result(method, Outcome.BLOCKED, rttMs, "закрыто без ответа (FIN)")
                first == 0x16 ->
                    Result(method, Outcome.PASS, rttMs, "TLS ServerHello получен")
                first == 0x15 ->
                    Result(method, Outcome.BLOCKED, rttMs, "TLS alert (отказ handshake)")
                else ->
                    Result(method, Outcome.BLOCKED, rttMs, "не-TLS ответ 0x${(first and 0xFF).toString(16)} (перехват)")
            }
        } catch (e: IOException) {
            val msg = (e.message ?: "").lowercase()
            val blocked = "reset" in msg || "econnreset" in msg || "broken pipe" in msg || "epipe" in msg
            return if (blocked) {
                Result(method, Outcome.BLOCKED, elapsedMs(startNs), "сброс соединения (DPI)")
            } else {
                Result(method, Outcome.ERROR, elapsedMs(startNs), e.message ?: "ошибка сети")
            }
        } catch (e: Exception) {
            return Result(method, Outcome.ERROR, elapsedMs(startNs), e.message ?: "ошибка")
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    private fun elapsedMs(fromNs: Long) = (System.nanoTime() - fromNs) / 1_000_000

    /**
     * Build a reasonably complete TLS 1.3 ClientHello with [sni] as the server_name. It doesn't
     * need to complete a handshake — it only needs a real SNI extension so we can observe whether
     * the provider resets the flow. Extensions included: server_name, supported_versions,
     * supported_groups, ec_point_formats, signature_algorithms, key_share (x25519, random),
     * psk_key_exchange_modes, ALPN(h2/http1.1).
     */
    fun buildClientHello(sni: String): ByteArray {
        val host = sni.toByteArray(Charsets.US_ASCII)

        // ---- extensions ----
        val ext = ByteArrayOutputStream()

        // server_name (0x0000)
        val sniBody = ByteArrayOutputStream().apply {
            // server_name_list
            val entry = ByteArrayOutputStream().apply {
                write(0x00)                 // name_type = host_name
                u16(this, host.size)        // name length
                write(host)
            }.toByteArray()
            u16(this, entry.size)           // server_name_list length
            write(entry)
        }.toByteArray()
        extension(ext, 0x0000, sniBody)

        // supported_versions (0x002b): TLS1.3, TLS1.2
        extension(ext, 0x002b, ByteArrayOutputStream().apply {
            write(0x04)                     // list length (bytes)
            u16(this, 0x0304); u16(this, 0x0303)
        }.toByteArray())

        // supported_groups (0x000a): x25519, secp256r1, secp384r1
        extension(ext, 0x000a, ByteArrayOutputStream().apply {
            u16(this, 6)
            u16(this, 0x001d); u16(this, 0x0017); u16(this, 0x0018)
        }.toByteArray())

        // ec_point_formats (0x000b): uncompressed
        extension(ext, 0x000b, byteArrayOf(0x01, 0x00))

        // signature_algorithms (0x000d)
        val sigs = intArrayOf(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601)
        extension(ext, 0x000d, ByteArrayOutputStream().apply {
            u16(this, sigs.size * 2)
            for (s in sigs) u16(this, s)
        }.toByteArray())

        // key_share (0x0033): x25519 with 32 random bytes (enough to not be rejected outright)
        val keyShareBody = ByteArrayOutputStream().apply {
            val entry = ByteArrayOutputStream().apply {
                u16(this, 0x001d)           // group x25519
                u16(this, 32)
                write(Random.nextBytes(32))
            }.toByteArray()
            u16(this, entry.size)
            write(entry)
        }.toByteArray()
        extension(ext, 0x0033, keyShareBody)

        // psk_key_exchange_modes (0x002d): psk_dhe_ke
        extension(ext, 0x002d, byteArrayOf(0x01, 0x01))

        // ALPN (0x0010): h2, http/1.1
        extension(ext, 0x0010, ByteArrayOutputStream().apply {
            val protos = ByteArrayOutputStream().apply {
                write(0x02); write("h2".toByteArray())
                write(0x08); write("http/1.1".toByteArray())
            }.toByteArray()
            u16(this, protos.size)
            write(protos)
        }.toByteArray())

        val extensions = ext.toByteArray()

        // ---- ClientHello body ----
        val body = ByteArrayOutputStream().apply {
            u16(this, 0x0303)               // legacy_version TLS1.2
            write(Random.nextBytes(32))     // random
            write(0x20); write(Random.nextBytes(32))  // session_id (32)
            // cipher_suites
            val ciphers = intArrayOf(0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030)
            u16(this, ciphers.size * 2)
            for (c in ciphers) u16(this, c)
            // compression_methods
            write(0x01); write(0x00)
            // extensions
            u16(this, extensions.size)
            write(extensions)
        }.toByteArray()

        // ---- handshake header ----
        val handshake = ByteArrayOutputStream().apply {
            write(0x01)                     // ClientHello
            u24(this, body.size)
            write(body)
        }.toByteArray()

        // ---- TLS record ----
        return ByteArrayOutputStream().apply {
            write(0x16)                     // handshake
            u16(this, 0x0301)               // record version TLS1.0 (typical)
            u16(this, handshake.size)
            write(handshake)
        }.toByteArray()
    }

    private fun u16(o: ByteArrayOutputStream, v: Int) { o.write((v ushr 8) and 0xFF); o.write(v and 0xFF) }
    private fun u24(o: ByteArrayOutputStream, v: Int) { o.write((v ushr 16) and 0xFF); o.write((v ushr 8) and 0xFF); o.write(v and 0xFF) }
    private fun extension(o: ByteArrayOutputStream, type: Int, body: ByteArray) {
        u16(o, type); u16(o, body.size); o.write(body)
    }
}
