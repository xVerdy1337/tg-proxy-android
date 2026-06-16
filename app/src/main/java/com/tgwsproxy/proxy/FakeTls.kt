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
    private const val TIMESTAMP_TOLERANCE = 120
    private const val TLS_APPDATA_MAX = 16384

    private val secureRandom = SecureRandom()

    private val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)

    // ServerHello skeleton (122-byte record). Offsets: random@11, sessionId@44, pubkey@89.
    private val SERVER_HELLO_TEMPLATE: ByteArray = run {
        val b = ArrayList<Byte>()
        fun add(vararg v: Int) = v.forEach { b.add(it.toByte()) }
        fun zeros(n: Int) = repeat(n) { b.add(0) }
        add(0x16, 0x03, 0x03, 0x00, 0x7a)      // record header
        add(0x02, 0x00, 0x00, 0x76)            // handshake header (ServerHello)
        add(0x03, 0x03)                        // version
        zeros(32)                              // server random (filled later)
        add(0x20)                              // session id length
        zeros(32)                              // session id (filled later)
        add(0x13, 0x01, 0x00)                  // cipher suite + compression
        add(0x00, 0x2e)                        // extensions length
        add(0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20) // key_share header
        zeros(32)                              // key_share pubkey (filled later)
        add(0x00, 0x2b, 0x00, 0x02, 0x03, 0x04) // supported_versions TLS1.3
        b.toByteArray()
    }
    private const val SH_RANDOM_OFF = 11
    private const val SH_SESSID_OFF = 44
    private const val SH_PUBKEY_OFF = 89

    data class ClientHello(val clientRandom: ByteArray, val sessionId: ByteArray, val timestamp: Long)

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
        return ClientHello(clientRandom, sessionId, timestamp)
    }

    /** Build the ServerHello (+ CCS + dummy app-data) keyed to the client's hello. */
    fun buildServerHello(secret: ByteArray, clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val sh = SERVER_HELLO_TEMPLATE.copyOf()
        System.arraycopy(sessionId, 0, sh, SH_SESSID_OFF, 32)
        val pub = ByteArray(32).also { secureRandom.nextBytes(it) }
        System.arraycopy(pub, 0, sh, SH_PUBKEY_OFF, 32)

        val encryptedSize = 1900 + secureRandom.nextInt(201) // 1900..2100
        val encryptedData = ByteArray(encryptedSize).also { secureRandom.nextBytes(it) }
        val appRecord = byteArrayOf(0x17, 0x03, 0x03) +
            ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(encryptedSize.toShort()).array() +
            encryptedData

        val response = sh + CCS_FRAME + appRecord

        val serverRandom = hmacSha256(secret, clientRandom + response)
        val finalResp = response.copyOf()
        System.arraycopy(serverRandom, 0, finalResp, SH_RANDOM_OFF, 32)
        return finalResp
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
