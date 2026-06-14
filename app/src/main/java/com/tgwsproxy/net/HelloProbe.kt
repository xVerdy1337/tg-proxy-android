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
 *  - the provider DPI (TSPU) that blocks YouTube/Instagram resets the connection right after it
 *    reads the SNI  -> we observe "Connection reset"  -> [Outcome.BLOCKED]
 *  - if the SNI is hidden by the desync, the connection survives and the server answers (or just
 *    keeps the socket open) -> [Outcome.PASS]
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

    /** Run one probe: connect, send the (maybe re-framed) ClientHello, classify the reaction. */
    fun probe(host: String, port: Int = 443, method: Method): Result {
        val start = System.currentTimeMillis()
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
                    for (chunk in plan.chunks) {
                        out.write(chunk); out.flush()
                        try { Thread.sleep(8) } catch (_: InterruptedException) {}
                    }
                }
            }

            sock.soTimeout = READ_TIMEOUT_MS
            val ins = sock.getInputStream()
            val first = try {
                ins.read()
            } catch (e: SocketTimeoutException) {
                // No reset within the window -> connection is alive -> DPI didn't kill it.
                return Result(method, Outcome.PASS, elapsed(start), "соединение живо (нет сброса)")
            }
            return if (first >= 0) {
                Result(method, Outcome.PASS, elapsed(start), "сервер ответил (0x${(first and 0xFF).toString(16)})")
            } else {
                // Clean FIN, no RST: SNI made it through without a provider reset.
                Result(method, Outcome.PASS, elapsed(start), "сервер закрыл без сброса")
            }
        } catch (e: IOException) {
            val msg = (e.message ?: "").lowercase()
            val blocked = "reset" in msg || "econnreset" in msg || "broken pipe" in msg || "epipe" in msg
            return if (blocked) {
                Result(method, Outcome.BLOCKED, elapsed(start), "сброс соединения (DPI)")
            } else {
                Result(method, Outcome.ERROR, elapsed(start), e.message ?: "ошибка сети")
            }
        } catch (e: Exception) {
            return Result(method, Outcome.ERROR, elapsed(start), e.message ?: "ошибка")
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    private fun elapsed(start: Long) = System.currentTimeMillis() - start

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
