package com.tgwsproxy.desync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesyncEngineTest {

    /** Build a minimal but structurally-valid TLS ClientHello carrying an SNI for [host]. */
    private fun clientHello(host: String): ByteArray {
        val h = host.toByteArray(Charsets.US_ASCII)
        fun be16(v: Int) = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

        val nameEntry = byteArrayOf(0x00) + be16(h.size) + h          // host_name type + len + host
        val sniList = be16(nameEntry.size) + nameEntry
        val extSni = be16(0x0000) + be16(sniList.size) + sniList
        val extDummy = be16(0x002b) + be16(2) + byteArrayOf(0x03, 0x04) // a second ext before SNI
        val exts = extDummy + extSni
        val extBlock = be16(exts.size) + exts

        var body = byteArrayOf(0x03, 0x03) + ByteArray(32) { 0x11 }   // version + random
        body += byteArrayOf(0x00)                                     // session_id len 0
        body += be16(2) + byteArrayOf(0x13, 0x01)                     // cipher suites
        body += byteArrayOf(0x01, 0x00)                               // compression methods
        body += extBlock

        val hsLen = body.size
        val handshake = byteArrayOf(0x01,
            ((hsLen ushr 16) and 0xFF).toByte(),
            ((hsLen ushr 8) and 0xFF).toByte(),
            (hsLen and 0xFF).toByte()) + body
        return byteArrayOf(0x16, 0x03, 0x01) + be16(handshake.size) + handshake
    }

    @Test
    fun detectsClientHello() {
        assertTrue(DesyncEngine.isClientHello(clientHello("www.youtube.com")))
        assertTrue(!DesyncEngine.isClientHello(byteArrayOf(0x17, 0x03, 0x03, 0, 5, 1, 2, 3)))
    }

    @Test
    fun findsSniOffset() {
        val host = "www.youtube.com"
        val ch = clientHello(host)
        val off = DesyncEngine.sniHostnameOffset(ch)!!
        val slice = ch.copyOfRange(off, off + host.length)
        assertArrayEquals(host.toByteArray(Charsets.US_ASCII), slice)
    }

    @Test
    fun nonTlsReturnedUnchanged() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6)
        val plan = DesyncEngine.plan(raw, DesyncEngine.Method.SPLIT)
        assertEquals(1, plan.chunks.size)
        assertArrayEquals(raw, plan.chunks[0])
    }

    @Test
    fun splitBreaksHostnameAndPreservesBytes() {
        val ch = clientHello("www.youtube.com")
        val plan = DesyncEngine.plan(ch, DesyncEngine.Method.SPLIT)
        assertEquals(2, plan.chunks.size)
        assertArrayEquals(ch, plan.chunks[0] + plan.chunks[1]) // concatenation == original
    }

    @Test
    fun tlsRecFragmentsReassembleToOriginalHandshake() {
        val ch = clientHello("discord.com")
        val off = DesyncEngine.sniHostnameOffset(ch)!!
        val recs = DesyncEngine.tlsRecordFragments(ch, off + 1)
        assertEquals(2, recs.size)
        // Strip each 5-byte record header and concat payloads → must equal original handshake.
        val reassembled = recs[0].copyOfRange(5, recs[0].size) + recs[1].copyOfRange(5, recs[1].size)
        assertArrayEquals(ch.copyOfRange(5, ch.size), reassembled)
        // Both fragments carry the same record type/version as the original.
        assertEquals(ch[0], recs[0][0]); assertEquals(ch[0], recs[1][0])
    }

    @Test
    fun disorderEmitsSecondSegmentFirst() {
        val ch = clientHello("instagram.com")
        val split = DesyncEngine.plan(ch, DesyncEngine.Method.SPLIT)
        val dis = DesyncEngine.plan(ch, DesyncEngine.Method.DISORDER)
        assertArrayEquals(split.chunks[0], dis.chunks[1])
        assertArrayEquals(split.chunks[1], dis.chunks[0])
    }

    @Test
    fun missingSniReturnsNull() {
        // ClientHello-ish header but truncated extensions → no SNI.
        val bogus = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00)
        assertNull(DesyncEngine.sniHostnameOffset(bogus))
    }
}
