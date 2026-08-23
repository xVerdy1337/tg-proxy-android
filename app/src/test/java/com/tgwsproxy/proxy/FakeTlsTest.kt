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
     * [cipherSuites] are appended as a proper vector after the session id so the parser has
     * something realistic to read.
     */
    private fun hello(
        secret: ByteArray,
        ageSeconds: Long,
        withSessionId: Boolean = true,
        cipherSuites: List<Int> = emptyList()
    ): ByteArray {
        val sessEnd = if (withSessionId) 76 else 44
        val buf = ByteArray(sessEnd + 2 + 2 * cipherSuites.size)
        buf[0] = 0x16
        buf[5] = 0x01
        if (withSessionId) {
            buf[43] = 0x20
            for (i in 0 until 32) buf[44 + i] = (i * 5).toByte()
        }
        val csLen = 2 * cipherSuites.size
        buf[sessEnd] = (csLen ushr 8).toByte()
        buf[sessEnd + 1] = csLen.toByte()
        cipherSuites.forEachIndexed { i, cs ->
            buf[sessEnd + 2 + 2 * i] = (cs ushr 8).toByte()
            buf[sessEnd + 3 + 2 * i] = cs.toByte()
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
        // hands back a zero-filled placeholder rather than a short array that would produce a
        // malformed session-id echo in the ServerHello.
        val secret = ByteArray(16) { (it + 1).toByte() }

        val res = FakeTls.verifyClientHello(hello(secret, 0, withSessionId = false), secret)

        assertNotNull(res)
        assertEquals(32, res.sessionId.size)
        assertContentEquals(ByteArray(32), res.sessionId)
    }

    /** A verified-hello stand-in for buildServerHello: fixed random/session, movable suites. */
    private fun clientHello(cipherSuites: List<Int> = listOf(0x1301)) = FakeTls.ClientHello(
        clientRandom = ByteArray(32) { (it * 3).toByte() },
        sessionId = ByteArray(32) { (it + 11).toByte() },
        timestamp = 0L,
        cipherSuites = cipherSuites
    )

    /** Parsed view of the ServerHello record at the start of a buildServerHello response. */
    private data class ParsedSh(
        val recordEnd: Int, // offset just past the ServerHello record (where CCS should start)
        val sessionId: ByteArray,
        val cipherSuite: Int,
        val extensions: List<Pair<Int, ByteArray>> // extension type to its data bytes
    )

    /**
     * Structurally parse the ServerHello. Tests must walk lengths rather than hard-code offsets,
     * because the record is assembled with shuffled extensions and optional padding — fixed
     * offsets were exactly the fingerprint this randomization removes.
     */
    private fun parseServerHello(resp: ByteArray): ParsedSh {
        require(u(resp[0]) == 0x16 && u(resp[5]) == 0x02) { "not a ServerHello record" }
        val recLen = (u(resp[3]) shl 8) or u(resp[4])
        val hsLen = (u(resp[6]) shl 16) or (u(resp[7]) shl 8) or u(resp[8])
        // record length = handshake header + handshake body; version(2)+random(32) then follow
        assertEquals(4 + hsLen, recLen, "record length must match the handshake length")
        assertEquals(0x03, u(resp[9]))
        assertEquals(0x03, u(resp[10]))

        val sessLen = u(resp[43])
        val sessionId = resp.copyOfRange(44, 44 + sessLen)
        val cipherOff = 44 + sessLen
        val cipher = (u(resp[cipherOff]) shl 8) or u(resp[cipherOff + 1])
        assertEquals(0x00, u(resp[cipherOff + 2]), "null compression")

        val extLenOff = cipherOff + 3
        val extLen = (u(resp[extLenOff]) shl 8) or u(resp[extLenOff + 1])
        val exts = mutableListOf<Pair<Int, ByteArray>>()
        var p = extLenOff + 2
        val extEnd = p + extLen
        assertEquals(9 + hsLen, extEnd, "extensions must end exactly at the handshake boundary")
        while (p < extEnd) {
            assertTrue(p + 4 <= extEnd, "truncated extension header")
            val type = (u(resp[p]) shl 8) or u(resp[p + 1])
            val len = (u(resp[p + 2]) shl 8) or u(resp[p + 3])
            assertTrue(p + 4 + len <= extEnd, "extension overruns the extensions block")
            exts += type to resp.copyOfRange(p + 4, p + 4 + len)
            p += 4 + len
        }
        assertEquals(extEnd, p, "extension walk must consume the block exactly")
        return ParsedSh(5 + recLen, sessionId, cipher, exts)
    }

    @Test
    fun serverHelloCarriesTheRandomARealClientWouldCheck() {
        // A genuine client recomputes HMAC(secret, clientRandom + response-with-random-zeroed) and
        // walks away if it does not match the server random. Getting this wrong makes the proxy
        // detectable by anyone who bothers to check, so assert it the way the client does.
        val secret = ByteArray(16) { (it + 7).toByte() }
        val hello = clientHello()

        val resp = FakeTls.buildServerHello(secret, hello)
        val sh = parseServerHello(resp)

        assertContentEquals(hello.sessionId, sh.sessionId, "the session id must be echoed")

        val zeroed = resp.copyOf()
        for (i in 0 until 32) zeroed[11 + i] = 0
        assertContentEquals(
            hmac(secret, hello.clientRandom + zeroed).copyOf(32),
            resp.copyOfRange(11, 43),
            "server random must be HMAC(secret, clientRandom + response)"
        )

        // CCS then a dummy app-data record of 1900..2100 bytes, right after the ServerHello.
        assertEquals(0x14, u(resp[sh.recordEnd]), "change-cipher-spec must follow the ServerHello")
        val appOff = sh.recordEnd + 6
        assertEquals(0x17, u(resp[appOff]), "then a dummy application-data record")
        val appLen = (u(resp[appOff + 3]) shl 8) or u(resp[appOff + 4])
        assertTrue(appLen in 1900..2100, "unexpected dummy app-data size $appLen")
        assertEquals(appOff + 5 + appLen, resp.size, "response must end with the app-data record")
    }

    @Test
    fun serverHelloUsesAFreshKeySharePerHello() {
        // The key_share pubkey must be freshly random per hello — a static value would make every
        // session fingerprintable. Located by walking extensions, not by offset.
        val secret = ByteArray(16) { (it + 1).toByte() }

        val first = parseServerHello(FakeTls.buildServerHello(secret, clientHello()))
        val second = parseServerHello(FakeTls.buildServerHello(secret, clientHello()))

        fun keySharePubkey(sh: ParsedSh): ByteArray {
            val ks = sh.extensions.firstOrNull { it.first == 0x0033 }
            assertNotNull(ks, "key_share extension must be present")
            // group(2)=x25519 + length(2)=32 + pubkey(32)
            assertEquals(36, ks.second.size)
            assertEquals(0x001d, (u(ks.second[0]) shl 8) or u(ks.second[1]), "x25519 group")
            return ks.second.copyOfRange(4, 36)
        }
        assertTrue(
            !keySharePubkey(first).contentEquals(keySharePubkey(second)),
            "key_share pubkey must differ between hellos"
        )
    }

    @Test
    fun serverHelloEchoesTheFirstSupportedClientCipher() {
        // Real servers pick the first client-preferred suite they support; echoing a constant
        // 0x1301 regardless of the offer is a trivially checkable tell.
        val secret = ByteArray(16) { (it + 1).toByte() }

        val resp = FakeTls.buildServerHello(secret, clientHello(listOf(0x1302, 0x1301)))
        assertEquals(0x1302, parseServerHello(resp).cipherSuite, "client prefers 0x1302, so we answer 0x1302")

        val resp2 = FakeTls.buildServerHello(secret, clientHello(listOf(0x1303, 0x1301, 0x1302)))
        assertEquals(0x1303, parseServerHello(resp2).cipherSuite)

        // Unsupported suites ahead of a supported one must be skipped, not echoed.
        val resp3 = FakeTls.buildServerHello(secret, clientHello(listOf(0x009c, 0x1302)))
        assertEquals(0x1302, parseServerHello(resp3).cipherSuite)
    }

    @Test
    fun serverHelloFallsBackToAes128GcmWhenNoCipherMatches() {
        val secret = ByteArray(16) { (it + 1).toByte() }

        val noneOffered = FakeTls.buildServerHello(secret, clientHello(emptyList()))
        assertEquals(0x1301, parseServerHello(noneOffered).cipherSuite)

        val nothingSupported = FakeTls.buildServerHello(secret, clientHello(listOf(0x009c, 0x002f)))
        assertEquals(0x1301, parseServerHello(nothingSupported).cipherSuite)
    }

    @Test
    fun serverHelloStructureIsValidWithAndWithoutPadding() {
        // Padding (~50% chance, 0..16 bytes) and shuffled extension order change the layout on
        // every call; over enough samples both padded and unpadded shapes must parse cleanly.
        val secret = ByteArray(16) { (it + 1).toByte() }
        var sawPadding = false
        var sawNoPadding = false
        val sawOrders = mutableSetOf<List<Int>>()

        repeat(60) {
            val sh = parseServerHello(FakeTls.buildServerHello(secret, clientHello()))
            val types = sh.extensions.map { it.first }
            assertTrue(0x0033 in types && 0x002b in types, "key_share and supported_versions required")
            val sv = sh.extensions.first { it.first == 0x002b }
            assertContentEquals(byteArrayOf(0x03, 0x04), sv.second, "supported_versions must offer TLS 1.3")
            if (0x0015 in types) {
                sawPadding = true
                val pad = sh.extensions.first { it.first == 0x0015 }
                assertTrue(pad.second.size in 0..16, "padding length 0..16, got ${pad.second.size}")
                assertContentEquals(ByteArray(pad.second.size), pad.second, "padding must be zero bytes")
            } else {
                sawNoPadding = true
            }
            sawOrders += types
        }
        assertTrue(sawPadding && sawNoPadding, "60 samples should cover both padding outcomes")
        assertTrue(sawOrders.size > 1, "extension order must vary across hellos")
    }

    @Test
    fun verifyClientHelloParsesTheOfferedCipherSuites() {
        val secret = ByteArray(16) { (it + 1).toByte() }

        val res = FakeTls.verifyClientHello(hello(secret, 0, cipherSuites = listOf(0x1302, 0x1301)), secret)
        assertNotNull(res)
        assertEquals(listOf(0x1302, 0x1301), res.cipherSuites)

        // And the parsed offer drives the ServerHello cipher choice end-to-end.
        val resp = FakeTls.buildServerHello(secret, res)
        assertEquals(0x1302, parseServerHello(resp).cipherSuite)
    }

    @Test
    fun verifyClientHelloToleratesTruncatedCipherSuites() {
        // Hostile or truncated hellos must degrade to an empty offer (cipher fallback), never crash.
        val secret = ByteArray(16) { (it + 1).toByte() }

        // No cipher-suites vector at all (buffer ends right after the session id).
        val res = FakeTls.verifyClientHello(hello(secret, 0), secret)
        assertNotNull(res)
        assertEquals(emptyList(), res.cipherSuites)
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
