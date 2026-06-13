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
}
