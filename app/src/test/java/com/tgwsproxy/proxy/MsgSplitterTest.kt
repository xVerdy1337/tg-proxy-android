package com.tgwsproxy.proxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun holdsOneToThreeByteIntermediateHeaderUntilComplete() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
        val plain = intHeader(4) + ByteArray(4) { 5 }
        val cipher = encryptor().update(plain)
        for (size in 1..3) {
            val local = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
            assertTrue(local.split(cipher.copyOfRange(0, size)).isEmpty())
            assertContentEquals(cipher, local.split(cipher.copyOfRange(size, cipher.size)).single())
        }
    }

    @Test
    fun inputArraysAreNotMutated() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
        val plain = intHeader(4) + ByteArray(4) { 7 }
        val cipher = encryptor().update(plain)
        val original = cipher.copyOf()
        splitter.split(cipher)
        assertContentEquals(original, cipher)
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
    fun emitsCompletePacketsAndRetainsPartialTrailingPacket() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
        val first = intHeader(4) + ByteArray(4) { 1 }
        val second = intHeader(8) + ByteArray(8) { 2 }
        val cipher = encryptor().update(first + second)
        val splitPoint = first.size + 5

        val parts = splitter.split(cipher.copyOfRange(0, splitPoint))
        assertEquals(1, parts.size)
        assertContentEquals(cipher.copyOfRange(0, first.size), parts.single())

        val tail = splitter.split(cipher.copyOfRange(splitPoint, cipher.size))
        assertEquals(1, tail.size)
        assertContentEquals(cipher.copyOfRange(first.size, cipher.size), tail.single())
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

    @Test
    fun abridgedReadsTheExtendedLengthHeader() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_ABRIDGED_INT)
        // 0x7F switches abridged to a 4-byte header whose 24-bit LE body counts 4-byte words.
        // 100 words = 400 payload bytes, so the packet is 404 bytes.
        val plain = byteArrayOf(0x7F, 100, 0, 0) + ByteArray(400) { 6 }
        val cipher = encryptor().update(plain)

        val parts = splitter.split(cipher)

        assertEquals(1, parts.size)
        assertEquals(404, parts[0].size)
        assertContentEquals(cipher, parts[0])
    }

    @Test
    fun abridgedAcceptsTheFFMarkerAsAnExtendedLengthHeader() {
        // 0xFF takes the same 4-byte long-header branch as 0x7F (1 word = 4 payload bytes).
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_ABRIDGED_INT)
        val plain = byteArrayOf(0xFF.toByte(), 1, 0, 0) + ByteArray(4) { 8 }
        val cipher = encryptor().update(plain)

        val parts = splitter.split(cipher)

        assertEquals(1, parts.size)
        assertEquals(8, parts[0].size)
        assertContentEquals(cipher, parts[0])
    }

    @Test
    fun abridgedStripsTheQuickAckBitFromShortHeaders() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_ABRIDGED_INT)
        // The high bit marks a quick-ack request; the length lives in the low 7 bits. Reading the
        // byte whole would give 130 words (520 bytes) instead of 2 words (8) and swallow the next
        // packet plus everything after it.
        val plain = byteArrayOf(0x82.toByte()) + ByteArray(8) { 7 } + byteArrayOf(1) + ByteArray(4) { 8 }
        val cipher = encryptor().update(plain)

        val parts = splitter.split(cipher)

        assertEquals(2, parts.size)
        assertEquals(9, parts[0].size)
        assertEquals(5, parts[1].size)
        assertContentEquals(cipher, parts[0] + parts[1])
    }

    @Test
    fun intermediateMasksOffTheQuickAckBit() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
        // Length 8 with bit 31 set. Unmasked this reads as a negative Int, and `payloadLen <= 0`
        // would disable splitting for the rest of the connection.
        val plain = byteArrayOf(0x08, 0x00, 0x00, 0x80.toByte()) + ByteArray(8) { 9 }
        val cipher = encryptor().update(plain)

        val parts = splitter.split(cipher)

        assertEquals(1, parts.size)
        assertEquals(12, parts[0].size)
        assertContentEquals(cipher, parts[0])
    }

    @Test
    fun paddedIntermediateFramesLikeIntermediate() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_PADDED_INTERMEDIATE_INT)
        val plain = intHeader(8) + ByteArray(8) { 1 } + intHeader(12) + ByteArray(12) { 2 }
        val cipher = encryptor().update(plain)

        val parts = splitter.split(cipher)

        assertEquals(2, parts.size)
        assertEquals(12, parts[0].size)
        assertEquals(16, parts[1].size)
    }

    @Test
    fun abridgedExtendedHeaderIsHeldUntilAllFourHeaderBytesArrive() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_ABRIDGED_INT)
        val plain = byteArrayOf(0x7F, 2, 0, 0) + ByteArray(8) { 4 }
        val cipher = encryptor().update(plain)
        assertTrue(splitter.split(cipher.copyOfRange(0, 3)).isEmpty())
        assertContentEquals(cipher, splitter.split(cipher.copyOfRange(3, cipher.size)).single())
    }

    @Test
    fun anAbsurdDeclaredLengthPassesTheStreamThroughInsteadOfBuffering() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
        // 20 MiB is past MAX_PACKET_LEN, so this is not framing we understand. Waiting for the
        // bytes to arrive would buffer toward OOM; the contract is to give up splitting and let
        // the relay keep forwarding, because the stream may still be a protocol we do not parse.
        val plain = intHeader(20 * 1024 * 1024) + ByteArray(32) { 3 }
        val cipher = encryptor().update(plain)

        val parts = splitter.split(cipher)

        assertEquals(1, parts.size)
        assertContentEquals(cipher, parts[0])

        // Disabled is sticky: later chunks pass straight through rather than being re-parsed.
        val more = ByteArray(50) { 4 }
        assertContentEquals(more, splitter.split(more).single())
    }

    @Test
    fun aZeroLengthPacketStopsSplittingRatherThanSpinning() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
        // A declared length of 0 would advance the offset by nothing — an infinite loop if it were
        // treated as a real packet.
        val cipher = encryptor().update(intHeader(0) + ByteArray(16) { 5 })

        val parts = splitter.split(cipher)

        assertEquals(1, parts.size)
        assertContentEquals(cipher, parts[0])
    }

    @Test
    fun anUnknownProtocolIsForwardedUnsplit() {
        val splitter = MsgSplitter(relayInit, 0x12345678L)
        val chunk = ByteArray(40) { it.toByte() }
        assertContentEquals(chunk, splitter.split(chunk).single())
    }

    @Test
    fun flushEmitsTheHeldPartialPacket() {
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)
        val cipher = encryptor().update(intHeader(64) + ByteArray(64) { 2 })

        val held = cipher.copyOfRange(0, 20)
        assertTrue(splitter.split(held).isEmpty())

        // On close the relay flushes so the tail is not silently dropped.
        assertContentEquals(held, splitter.flush().single())
        assertTrue(splitter.flush().isEmpty(), "flush must not re-emit an already drained buffer")
    }

    @Test
    fun rejectsARelayInitTooShortToCarryKeyAndIv() {
        assertFailsWith<IllegalArgumentException> {
            MsgSplitter(ByteArray(40), MtProtoConstants.PROTO_INTERMEDIATE_INT)
        }
    }
}
