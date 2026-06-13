package com.tgwsproxy.proxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MsgSplitterTest {

    private val relayInit = ByteArray(64) { (it * 7 + 3).toByte() }

    /** A keystream-aligned encryptor identical to the one MsgSplitter decrypts with. */
    private fun encryptor(): Cipher {
        val key = relayInit.copyOfRange(8, 40)
        val iv = relayInit.copyOfRange(40, 56)
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            update(ByteArray(64)) // ZERO_64 fast-forward, mirrors MsgSplitter
        }
    }

    private fun intHeader(len: Int) = byteArrayOf(
        (len and 0xFF).toByte(),
        ((len ushr 8) and 0xFF).toByte(),
        ((len ushr 16) and 0xFF).toByte(),
        ((len ushr 24) and 0xFF).toByte()
    )

    @Test
    fun splitsIntermediatePacketsAtBoundaries() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
        val plain = intHeader(8) + ByteArray(8) { 1 } + intHeader(4) + ByteArray(4) { 2 }
        val cipher = encryptor().update(plain)

        val parts = splitter.split(cipher)

        assertEquals(2, parts.size)
        assertEquals(12, parts[0].size) // 4-byte header + 8 payload
        assertEquals(8, parts[1].size)  // 4-byte header + 4 payload
        // Output is the original cipher stream, just cut into frames.
        assertContentEquals(cipher, parts[0] + parts[1])
    }

    @Test
    fun holdsPartialPacketUntilComplete() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
        val plain = intHeader(8) + ByteArray(8) { 5 }
        val cipher = encryptor().update(plain) // single 12-byte packet

        val first = splitter.split(cipher.copyOfRange(0, 6))
        assertTrue(first.isEmpty()) // not enough bytes yet

        val rest = splitter.split(cipher.copyOfRange(6, cipher.size))
        assertEquals(1, rest.size)
        assertEquals(12, rest[0].size)
        assertContentEquals(cipher, rest[0])
    }

    @Test
    fun splitsAbridgedShortPacket() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_ABRIDGED_INT)
        // Abridged short header: first byte = payloadLen / 4 -> 2 means 8 payload bytes.
        val plain = byteArrayOf(2) + ByteArray(8) { 9 }
        val cipher = encryptor().update(plain)

        val parts = splitter.split(cipher)

        assertEquals(1, parts.size)
        assertEquals(9, parts[0].size)
        assertContentEquals(cipher, parts[0])
    }

    @Test
    fun emptyChunkYieldsNothing() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
        assertTrue(splitter.split(ByteArray(0)).isEmpty())
    }
}
