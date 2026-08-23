package com.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeTlsTest {

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun u(b: Byte) = b.toInt() and 0xFF

    @Test
    fun wrapTlsRecordSinglePayload() {
        val data = ByteArray(100) { it.toByte() }
        val wrapped = FakeTls.wrapTlsRecord(data)

        assertEquals(5 + 100, wrapped.size)
        assertEquals(0x17, u(wrapped[0]))
        assertEquals(0x03, u(wrapped[1]))
        assertEquals(0x03, u(wrapped[2]))
        assertEquals(100, (u(wrapped[3]) shl 8) or u(wrapped[4]))
        assertContentEquals(data, wrapped.copyOfRange(5, wrapped.size))
    }

    @Test
    fun wrapTlsRecordSplitsAt16384() {
        val data = ByteArray(16384 + 10) { (it % 256).toByte() }
        val wrapped = FakeTls.wrapTlsRecord(data)

        // record1 header(5) + 16384, record2 header(5) + 10
        assertEquals(5 + 16384 + 5 + 10, wrapped.size)
        assertEquals(16384, (u(wrapped[3]) shl 8) or u(wrapped[4])) // 0x4000
        val secondHeaderAt = 5 + 16384
        assertEquals(0x17, u(wrapped[secondHeaderAt]))
        assertEquals(10, (u(wrapped[secondHeaderAt + 3]) shl 8) or u(wrapped[secondHeaderAt + 4]))
    }

    @Test
    fun streamRoundTripAcrossMultipleRecords() {
        val payload = ByteArray(40000) { (it * 3).toByte() }
        val raw = ByteArrayOutputStream()
        FakeTlsOutputStream(raw).apply { write(payload); flush() }

        val read = FakeTlsInputStream(ByteArrayInputStream(raw.toByteArray())).readBytes()
        assertContentEquals(payload, read)
    }

    @Test
    fun inputStreamSkipsChangeCipherSpecRecords() {
        val ccs = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
        val app = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x02, 0x41, 0x42)
        val read = FakeTlsInputStream(ByteArrayInputStream(ccs + app)).readBytes()
        assertContentEquals(byteArrayOf(0x41, 0x42), read)
    }

    @Test
    fun verifyClientHelloRejectsGarbage() {
        val secret = ByteArray(16) { (it + 1).toByte() }
        assertNull(FakeTls.verifyClientHello(ByteArray(10), secret)) // too short
        val bad = ByteArray(80).also { it[0] = 0x16; it[5] = 0x01 } // wrong HMAC
        assertNull(FakeTls.verifyClientHello(bad, secret))
    }

    @Test
    fun verifyClientHelloAcceptsValidHello() {
        val secret = ByteArray(16) { (it + 1).toByte() }
        val buf = ByteArray(76)
        buf[0] = 0x16          // TLS handshake record
        buf[5] = 0x01          // ClientHello
        buf[43] = 0x20         // session id length = 32
        for (i in 0 until 32) buf[44 + i] = (i * 5).toByte() // session id

        // client-random region (11..43) stays zero -> matches verify's zeroed buffer
        val expected = hmac(secret, buf.copyOf())

        val ts = (System.currentTimeMillis() / 1000).toInt()
        val tsBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ts).array()
        val clientRandom = ByteArray(32)
        for (i in 0 until 28) clientRandom[i] = expected[i]
        for (i in 0 until 4) clientRandom[28 + i] = (tsBytes[i].toInt() xor expected[28 + i].toInt()).toByte()
        System.arraycopy(clientRandom, 0, buf, 11, 32)

        val res = FakeTls.verifyClientHello(buf, secret)
        assertNotNull(res)
        assertContentEquals(clientRandom, res.clientRandom)
        assertContentEquals(buf.copyOfRange(44, 76), res.sessionId)
    }

    /**
     * Build a ClientHello that verifies against [secret], stamped [ageSeconds] in the past.
     * Mirrors the hand-rolled construction above, with the timestamp and the session-id length
     * byte both movable — the latter sits inside the HMAC, so it must be set before signing.
     */
    private fun hello(secret: ByteArray, ageSeconds: Long, withSessionId: Boolean = true): ByteArray {
        val buf = ByteArray(76)
        buf[0] = 0x16
        buf[5] = 0x01
        if (withSessionId) {
            buf[43] = 0x20
            for (i in 0 until 32) buf[44 + i] = (i * 5).toByte()
        }

        // Signed with the client-random region zeroed, exactly as verifyClientHello recomputes it.
        val expected = hmac(secret, buf.copyOf())
        val ts = (System.currentTimeMillis() / 1000 - ageSeconds).toInt()
        val tsBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ts).array()
        for (i in 0 until 28) buf[11 + i] = expected[i]
        for (i in 0 until 4) {
            buf[11 + 28 + i] = (tsBytes[i].toInt() xor expected[28 + i].toInt()).toByte()
        }
        return buf
    }

    @Test
    fun verifyClientHelloRejectsAStaleTimestamp() {
        // The HMAC is valid, so this is the replay defence rather than authentication: a hello
        // captured earlier and re-sent must not open a session. Only the clock is out of range.
        val secret = ByteArray(16) { (it + 1).toByte() }
        assertNotNull(FakeTls.verifyClientHello(hello(secret, 0), secret))
        assertNull(FakeTls.verifyClientHello(hello(secret, 3600), secret))
        assertNull(FakeTls.verifyClientHello(hello(secret, -3600), secret))
    }

    @Test
    fun verifyClientHelloRejectsAHelloForADifferentSecret() {
        val secret = ByteArray(16) { (it + 1).toByte() }
        val other = ByteArray(16) { (it + 2).toByte() }
        val data = hello(secret, 0)
        assertNotNull(FakeTls.verifyClientHello(data, secret))
        assertNull(FakeTls.verifyClientHello(data, other))
    }

    @Test
    fun verifyClientHelloToleratesAMissingSessionId() {
        // Session id is optional; without one the reply still needs 32 bytes to echo, so verify
        // hands back a zero-filled placeholder rather than a short array that would overflow
        // buildServerHello's arraycopy.
        val secret = ByteArray(16) { (it + 1).toByte() }

        val res = FakeTls.verifyClientHello(hello(secret, 0, withSessionId = false), secret)

        assertNotNull(res)
        assertEquals(32, res.sessionId.size)
        assertContentEquals(ByteArray(32), res.sessionId)
    }

    @Test
    fun serverHelloCarriesTheRandomARealClientWouldCheck() {
        // A genuine client recomputes HMAC(secret, clientRandom + response-with-random-zeroed) and
        // walks away if it does not match the server random. Getting this wrong makes the proxy
        // detectable by anyone who bothers to check, so assert it the way the client does.
        val secret = ByteArray(16) { (it + 7).toByte() }
        val clientRandom = ByteArray(32) { (it * 3).toByte() }
        val sessionId = ByteArray(32) { (it + 11).toByte() }

        val resp = FakeTls.buildServerHello(secret, clientRandom, sessionId)

        assertEquals(0x16, u(resp[0]))
        assertEquals(0x03, u(resp[1]))
        assertEquals(0x03, u(resp[2]))
        assertEquals(122, (u(resp[3]) shl 8) or u(resp[4]))
        assertContentEquals(sessionId, resp.copyOfRange(44, 76), "the session id must be echoed")

        val zeroed = resp.copyOf()
        for (i in 0 until 32) zeroed[11 + i] = 0
        assertContentEquals(
            hmac(secret, clientRandom + zeroed).copyOf(32),
            resp.copyOfRange(11, 43),
            "server random must be HMAC(secret, clientRandom + response)"
        )

        // ServerHello(127) + CCS(6) + app-data header(5) + 1900..2100 random bytes.
        assertTrue(resp.size in (127 + 6 + 5 + 1900)..(127 + 6 + 5 + 2100), "unexpected size ${resp.size}")
        assertEquals(0x14, u(resp[127]), "change-cipher-spec must follow the ServerHello")
        assertEquals(0x17, u(resp[133]), "then a dummy application-data record")
    }

    @Test
    fun serverHelloUsesAFreshKeySharePerHello() {
        // The key_share pubkey (offset 89) must be freshly random per hello — a static template
        // value would make every session fingerprintable.
        val secret = ByteArray(16) { (it + 1).toByte() }
        val clientRandom = ByteArray(32) { (it * 3).toByte() }
        val sessionId = ByteArray(32) { (it + 11).toByte() }

        val first = FakeTls.buildServerHello(secret, clientRandom, sessionId)
        val second = FakeTls.buildServerHello(secret, clientRandom, sessionId)

        assertTrue(
            !first.copyOfRange(89, 121).contentEquals(second.copyOfRange(89, 121)),
            "key_share pubkey must differ between hellos"
        )
    }

    @Test
    fun inputStreamEndsTheStreamOnANonAppDataRecord() {
        // A TLS alert (or anything else) means the session is over. Treating it as payload would
        // splice record bytes into the obfuscated2 stream and desynchronise the keystream.
        val app = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x02, 0x41, 0x42)
        val alert = byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x01, 0x00)
        val trailing = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x01, 0x43)

        val read = FakeTlsInputStream(ByteArrayInputStream(app + alert + trailing)).readBytes()
        assertContentEquals(byteArrayOf(0x41, 0x42), read)
    }

    @Test
    fun inputStreamSkipsEmptyAppDataRecords() {
        val empty = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x00)
        val app = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x02, 0x41, 0x42)
        val read = FakeTlsInputStream(ByteArrayInputStream(empty + app + empty)).readBytes()
        assertContentEquals(byteArrayOf(0x41, 0x42), read)
    }

    @Test
    fun inputStreamStopsOnATruncatedRecordInsteadOfBlocking() {
        // A record header promising 100 bytes with 3 delivered is what a dropped connection looks
        // like mid-record: readFully throws, and that has to surface as EOF.
        val truncated = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x64, 0x41, 0x42, 0x43)
        val read = FakeTlsInputStream(ByteArrayInputStream(truncated)).readBytes()
        assertContentEquals(ByteArray(0), read)
    }

    @Test
    fun outputStreamFramesEveryWriteSeparately() {
        // Each write becomes its own record, so a 1-byte write must not be silently buffered into
        // a later one — the relay depends on writes reaching the wire when it makes them.
        val raw = ByteArrayOutputStream()
        FakeTlsOutputStream(raw).apply {
            write(byteArrayOf(0x41, 0x42))
            write(0x43)
            write(ByteArray(0))
            flush()
        }
        val out = raw.toByteArray()

        assertEquals(5 + 2 + 5 + 1, out.size, "two records, no record for the empty write")
        assertEquals(2, (u(out[3]) shl 8) or u(out[4]))
        assertEquals(1, (u(out[10]) shl 8) or u(out[11]))
        assertContentEquals(byteArrayOf(0x41, 0x42, 0x43), FakeTlsInputStream(ByteArrayInputStream(out)).readBytes())
    }

    @Test
    fun wrapTlsRecordIgnoresEmptyInput() {
        assertEquals(0, FakeTls.wrapTlsRecord(ByteArray(0)).size)
    }
}
