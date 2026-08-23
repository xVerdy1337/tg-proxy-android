package com.tgwsproxy.proxy

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fake TLS (a.k.a. `ee...` secrets) — DPI masking that makes the proxy traffic look like a
 * normal HTTPS session to a real domain. Ported bit-for-bit from the Flowseal reference
 * (`proxy/fake_tls.py`): verify the client's TLS ClientHello via HMAC, answer with a
 * matching ServerHello, then read the obfuscated2 init wrapped inside TLS application-data
 * records. After the handshake every byte to/from the client is framed in 0x17 records.
 */
object FakeTls {

    const val TLS_RECORD_HANDSHAKE = 0x16
    const val TLS_RECORD_CCS = 0x14
    const val TLS_RECORD_APPDATA = 0x17

    private const val CLIENT_RANDOM_OFFSET = 11
    private const val CLIENT_RANDOM_LEN = 32
    private const val SESSION_ID_OFFSET = 44
    private const val SESSION_ID_LEN = 32
    // Replay-tolerance window for the ClientHello timestamp, seconds. Protocol property, not a
    // tunable: a captured hello stays valid for 120s (needed to absorb clock skew on mobile
    // devices), and shortening it would break legit clients before it meaningfully hurt replay.
    private const val TIMESTAMP_TOLERANCE = 120
    private const val TLS_APPDATA_MAX = 16384

    private val secureRandom = SecureRandom()

    private val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)

    // TLS 1.3 cipher suites this fake server "supports". Anything else the client offers is
    // ignored, mirroring a real server that picks the first mutually supported suite.
    private val TLS13_CIPHERS = setOf(0x1301, 0x1302, 0x1303)
    private const val CIPHER_FALLBACK = 0x1301 // TLS_AES_128_GCM_SHA256

    // Server random always starts at byte 11 (5-byte record header + 4-byte handshake header +
    // 2-byte version), no matter how the extensions are shuffled or padded.
    private const val SH_RANDOM_OFF = 11

    data class ClientHello(
        val clientRandom: ByteArray,
        val sessionId: ByteArray,
        val timestamp: Long,
        // Cipher suites offered by the client, in its preference order. Needed so the
        // ServerHello can echo a suite the way a real server would, instead of a constant.
        val cipherSuites: List<Int> = emptyList()
    )

    /** Verify a TLS ClientHello against [secret]; null = not a valid Fake-TLS hello. */
    fun verifyClientHello(data: ByteArray, secret: ByteArray): ClientHello? {
        val n = data.size
        if (n < 43) return null
        if (data[0].toInt() and 0xFF != TLS_RECORD_HANDSHAKE) return null
        if (data[5].toInt() and 0xFF != 0x01) return null

        val clientRandom = data.copyOfRange(CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN)

        val zeroed = data.copyOf()
        for (i in 0 until CLIENT_RANDOM_LEN) zeroed[CLIENT_RANDOM_OFFSET + i] = 0

        val expected = hmacSha256(secret, zeroed)

        // First 28 bytes are the secret-derived HMAC tag — compare in constant time so the
        // network can't use response timing as an oracle to recover the tag byte-by-byte.
        if (!java.security.MessageDigest.isEqual(expected.copyOf(28), clientRandom.copyOf(28))) {
            return null
        }

        // Last 4 bytes are an XOR-masked little-endian unix timestamp.
        val tsXor = ByteArray(4) { (clientRandom[28 + it].toInt() xor expected[28 + it].toInt()).toByte() }
        val timestamp = ByteBuffer.wrap(tsXor).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

        val now = System.currentTimeMillis() / 1000
        if (kotlin.math.abs(now - timestamp) > TIMESTAMP_TOLERANCE) return null

        var sessionId = ByteArray(SESSION_ID_LEN)
        if (n >= SESSION_ID_OFFSET + SESSION_ID_LEN && (data[43].toInt() and 0xFF) == 0x20) {
            sessionId = data.copyOfRange(SESSION_ID_OFFSET, SESSION_ID_OFFSET + SESSION_ID_LEN)
        }
        return ClientHello(clientRandom, sessionId, timestamp, parseCipherSuites(data))
    }

    /**
     * Walk the cipher-suites vector that follows the session id in a ClientHello
     * (1-byte session-id length, then a 2-byte vector length, then 2 bytes per suite).
     * Bounds-checked at every step: this runs on unauthenticated wire bytes, and a truncated
     * or hostile hello must degrade to an empty list (cipher fallback), never to a crash.
     */
    private fun parseCipherSuites(data: ByteArray): List<Int> {
        val n = data.size
        if (n <= SESSION_ID_OFFSET - 1) return emptyList() // no session-id length byte
        val sessLen = data[SESSION_ID_OFFSET - 1].toInt() and 0xFF
        val csOff = SESSION_ID_OFFSET + sessLen
        if (n < csOff + 2) return emptyList()
        val csLen = ((data[csOff].toInt() and 0xFF) shl 8) or (data[csOff + 1].toInt() and 0xFF)
        val avail = minOf(csLen, n - csOff - 2)
        val suites = ArrayList<Int>(avail / 2)
        var i = 0
        while (i + 2 <= avail) {
            suites += ((data[csOff + 2 + i].toInt() and 0xFF) shl 8) or
                (data[csOff + 3 + i].toInt() and 0xFF)
            i += 2
        }
        return suites
    }

    /**
     * Build the ServerHello (+ CCS + dummy app-data) keyed to the client's hello.
     *
     * The record is assembled programmatically instead of stamped from a fixed template,
     * because a byte-identical 122-byte skeleton is a static JA3S-style fingerprint any DPI
     * box can flag. Three axes of per-connection variability, all drawn from SecureRandom:
     *  - the cipher suite echoes the first client-offered suite we "support" (what a real
     *    server does), falling back to TLS_AES_128_GCM_SHA256 when nothing matches;
     *  - key_share / supported_versions are emitted in random order;
     *  - a padding extension (0x0015) of random length 0..16 is added with ~50% probability.
     * Every length field (record, handshake, extensions) is recomputed from the actual bytes.
     */
    fun buildServerHello(secret: ByteArray, hello: ClientHello): ByteArray {
        val cipher = hello.cipherSuites.firstOrNull { it in TLS13_CIPHERS } ?: CIPHER_FALLBACK

        // key_share: x25519 (0x001d) with a fresh 32-byte pubkey per hello — a reused pubkey
        // would re-introduce a static fingerprint even with everything else randomized.
        val pub = ByteArray(32).also { secureRandom.nextBytes(it) }
        val keyShare = byteArrayOf(0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20) + pub
        val supportedVersions = byteArrayOf(0x00, 0x2b, 0x00, 0x02, 0x03, 0x04)

        val exts = arrayListOf(keyShare, supportedVersions)
        if (secureRandom.nextBoolean()) {
            val padLen = secureRandom.nextInt(17) // 0..16
            exts += byteArrayOf(0x00, 0x15, (padLen ushr 8).toByte(), padLen.toByte()) + ByteArray(padLen)
        }
        // Fisher-Yates with SecureRandom: extension order must not be stable across sessions.
        for (i in exts.size - 1 downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val tmp = exts[i]; exts[i] = exts[j]; exts[j] = tmp
        }
        val extBlock = exts.fold(ByteArray(0)) { acc, e -> acc + e }

        // Echo the client's session id; copyOf(32) keeps the echo exactly 32 bytes even if a
        // hand-built ClientHello carried a shorter one.
        val sessionId = hello.sessionId.copyOf(32)
        val body = byteArrayOf(0x03, 0x03) +      // legacy version TLS 1.2
            ByteArray(32) +                        // server random, stamped after assembly
            byteArrayOf(sessionId.size.toByte()) + sessionId +
            byteArrayOf((cipher ushr 8).toByte(), cipher.toByte()) +
            byteArrayOf(0x00) +                    // null compression
            byteArrayOf(((extBlock.size ushr 8).toByte()), extBlock.size.toByte()) + extBlock
        val handshake = byteArrayOf(0x02,
            (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
        val sh = byteArrayOf(0x16, 0x03, 0x03,
            (handshake.size ushr 8).toByte(), handshake.size.toByte()) + handshake

        val encryptedSize = 1900 + secureRandom.nextInt(201) // 1900..2100
        val encryptedData = ByteArray(encryptedSize).also { secureRandom.nextBytes(it) }
        val appRecord = byteArrayOf(0x17, 0x03, 0x03) +
            ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(encryptedSize.toShort()).array() +
            encryptedData

        val response = sh + CCS_FRAME + appRecord

        // The server random authenticates everything the client will see, so it is stamped in
        // only after the full response (SH + CCS + dummy app-data) has been assembled.
        val serverRandom = hmacSha256(secret, hello.clientRandom + response)
        System.arraycopy(serverRandom, 0, response, SH_RANDOM_OFF, 32)
        return response
    }

    /** Wrap arbitrary bytes into one or more TLS application-data records. */
    fun wrapTlsRecord(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + TLS_APPDATA_MAX, data.size)
            val len = end - offset
            out.write(0x17); out.write(0x03); out.write(0x03)
            out.write((len ushr 8) and 0xFF); out.write(len and 0xFF)
            out.write(data, offset, len)
            offset = end
        }
        return out.toByteArray()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    @Suppress("unused")
    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
}

/**
 * InputStream that de-frames TLS application-data records on the fly, so the rest of the
 * proxy can read the inner obfuscated2 stream as if it were raw TCP. CCS records are
 * silently skipped; any non-app-data record ends the stream.
 */
class FakeTlsInputStream(raw: InputStream) : InputStream() {
    private val din = DataInputStream(raw)
    private var buffer: ByteArray = ByteArray(0)
    private var pos = 0
    private var eof = false

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= buffer.size) {
            if (!fill()) return -1
        }
        val avail = buffer.size - pos
        val n = minOf(avail, len)
        System.arraycopy(buffer, pos, b, off, n)
        pos += n
        return n
    }

    /** Pull the next non-empty app-data record payload into [buffer]; false on EOF. */
    private fun fill(): Boolean {
        while (!eof) {
            val header = ByteArray(5)
            try {
                din.readFully(header)
            } catch (_: Exception) {
                eof = true; return false
            }
            val rtype = header[0].toInt() and 0xFF
            val recLen = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)

            if (rtype == FakeTls.TLS_RECORD_CCS) {
                if (recLen > 0) {
                    try { din.skipBytes(recLen) } catch (_: Exception) { eof = true; return false }
                }
                continue
            }
            if (rtype != FakeTls.TLS_RECORD_APPDATA) {
                eof = true; return false
            }
            if (recLen == 0) continue
            val body = ByteArray(recLen)
            try {
                din.readFully(body)
            } catch (_: Exception) {
                eof = true; return false
            }
            buffer = body
            pos = 0
            return true
        }
        return false
    }
}

/** OutputStream that frames every write into TLS application-data records. */
class FakeTlsOutputStream(private val raw: OutputStream) : OutputStream() {
    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
        raw.write(FakeTls.wrapTlsRecord(slice))
    }

    override fun flush() {
        raw.flush()
    }
}
