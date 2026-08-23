package com.tgwsproxy.proxy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MtProtoHandshakeTest {

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    private fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.update(data)

    @Test
    fun wsDomainsNonMediaOrder() {
        assertEquals(
            listOf("kws2.web.telegram.org", "kws2-1.web.telegram.org"),
            MtProtoHandshake.wsDomains(2, isMedia = false)
        )
    }

    @Test
    fun wsDomainsMediaIsReversed() {
        assertEquals(
            listOf("kws2-1.web.telegram.org", "kws2.web.telegram.org"),
            MtProtoHandshake.wsDomains(2, isMedia = true)
        )
    }

    @Test
    fun wsDomainsMapsDc203ToDc2() {
        assertEquals(
            listOf("kws2.web.telegram.org", "kws2-1.web.telegram.org"),
            MtProtoHandshake.wsDomains(203, isMedia = false)
        )
    }

    @Test
    fun tryHandshakeRequiresFullLength() {
        assertFailsWith<IllegalArgumentException> {
            MtProtoHandshake.tryHandshake(ByteArray(10), ByteArray(16))
        }
    }

    @Test
    fun tryHandshakeRejectsUnknownProtoTag() {
        val secret = ByteArray(16) { (it + 3).toByte() }
        val hs = ByteArray(64) { (it * 13 + 7).toByte() } // decrypts to a non-tag
        assertNull(MtProtoHandshake.tryHandshake(hs, secret))
    }

    @Test
    fun tryHandshakeDecodesValidHandshake() {
        val secret = ByteArray(16) { (it + 3).toByte() }
        val hs = ByteArray(64)
        for (i in 0 until 56) hs[i] = (i * 9 + 1).toByte() // fixes key/iv + keystream

        val decKey = sha256(hs.copyOfRange(8, 40) + secret)
        val decIv = hs.copyOfRange(40, 56)
        val keystream = aesCtr(decKey, decIv, ByteArray(64))

        val protoTag = MtProtoConstants.PROTO_TAG_INTERMEDIATE
        val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(2.toShort()).array()
        // Patch bytes 56..64 so the decrypted plaintext carries our tag + dc index.
        for (i in 0 until 4) hs[56 + i] = (protoTag[i].toInt() xor keystream[56 + i].toInt()).toByte()
        for (i in 0 until 2) hs[60 + i] = (dcBytes[i].toInt() xor keystream[60 + i].toInt()).toByte()
        hs[62] = (5 xor keystream[62].toInt()).toByte()
        hs[63] = (6 xor keystream[63].toInt()).toByte()

        val res = MtProtoHandshake.tryHandshake(hs, secret)!!
        assertEquals(2, res.dcId)
        assertFalse(res.isMedia)
        assertContentEquals(protoTag, res.protoTag)
        assertContentEquals(hs.copyOfRange(8, 56), res.clientDecPrekeyIv)
    }

    @Test
    fun negativeDcIndexMeansMedia() {
        // Same construction as tryHandshakeDecodesValidHandshake, but with a negative dc index:
        // the sign bit is how the client signals a media DC, and losing it would route media
        // traffic to the wrong (non-media) front.
        val secret = ByteArray(16) { (it + 3).toByte() }
        val hs = ByteArray(64)
        for (i in 0 until 56) hs[i] = (i * 9 + 1).toByte()

        val decKey = sha256(hs.copyOfRange(8, 40) + secret)
        val decIv = hs.copyOfRange(40, 56)
        val keystream = aesCtr(decKey, decIv, ByteArray(64))

        val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((-2).toShort()).array()
        for (i in 0 until 4) hs[56 + i] = (MtProtoConstants.PROTO_TAG_INTERMEDIATE[i].toInt() xor keystream[56 + i].toInt()).toByte()
        for (i in 0 until 2) hs[60 + i] = (dcBytes[i].toInt() xor keystream[60 + i].toInt()).toByte()

        val res = MtProtoHandshake.tryHandshake(hs, secret)!!
        assertEquals(2, res.dcId) // media sessions report the absolute DC number
        assertTrue(res.isMedia)
    }

    @Test
    fun generatedRelayInitRoundTripsThroughRelaySideParsing() {
        // generateRelayInit and tryHandshake are OPPOSITE legs with different key derivations
        // (client leg hashes the prekey with the secret, relay leg uses the init's raw key),
        // so tryHandshake cannot parse a relay init by design. The generator is therefore
        // verified against the relay-side parse: raw key/iv straight from the init.
        val relayInit = MtProtoHandshake.generateRelayInit(MtProtoConstants.PROTO_TAG_INTERMEDIATE, 2)

        assertEquals(MtProtoConstants.HANDSHAKE_LEN, relayInit.size)

        val key = relayInit.copyOfRange(
            MtProtoConstants.SKIP_LEN,
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN
        )
        val iv = relayInit.copyOfRange(
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN,
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        )
        val decrypted = aesCtr(key, iv, relayInit)

        assertContentEquals(
            MtProtoConstants.PROTO_TAG_INTERMEDIATE,
            decrypted.copyOfRange(MtProtoConstants.PROTO_TAG_POS, MtProtoConstants.PROTO_TAG_POS + 4)
        )
        val dcIdx = ByteBuffer
            .wrap(decrypted.copyOfRange(MtProtoConstants.DC_IDX_POS, MtProtoConstants.DC_IDX_POS + 2))
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
        assertEquals(2, dcIdx.toInt())
    }

    @Test
    fun generatedRelayInitPreservesNegativeDcIndex() {
        // The sign of the dc index is the media-DC signal for everything downstream
        // (tryHandshake maps dcIdx < 0 to isMedia), so the generator must carry it through.
        val relayInit = MtProtoHandshake.generateRelayInit(MtProtoConstants.PROTO_TAG_SECURE, -203)

        val key = relayInit.copyOfRange(
            MtProtoConstants.SKIP_LEN,
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN
        )
        val iv = relayInit.copyOfRange(
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN,
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        )
        val decrypted = aesCtr(key, iv, relayInit)

        assertContentEquals(
            MtProtoConstants.PROTO_TAG_SECURE,
            decrypted.copyOfRange(MtProtoConstants.PROTO_TAG_POS, MtProtoConstants.PROTO_TAG_POS + 4)
        )
        val dcIdx = ByteBuffer
            .wrap(decrypted.copyOfRange(MtProtoConstants.DC_IDX_POS, MtProtoConstants.DC_IDX_POS + 2))
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
        assertEquals(-203, dcIdx.toInt())
    }

    @Test
    fun buildCryptoContextDerivesKeysAndFastForwardsSelectively() {
        val prekeyIv = ByteArray(MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN) { (it * 3 + 1).toByte() }
        val secret = ByteArray(16) { (it + 5).toByte() }
        val relayInit = ByteArray(64) { (it * 11 + 7).toByte() }

        val ctx = MtProtoHandshake.buildCryptoContext(prekeyIv, secret, relayInit)
        val msg = ByteArray(100) { (it * 2).toByte() }

        // Client decrypt: key = SHA256(prekey + secret), iv from the handshake, and the
        // keystream fast-forwarded past the client's 64-byte init already consumed.
        val cltDecKey = sha256(prekeyIv.copyOfRange(0, MtProtoConstants.PREKEY_LEN) + secret)
        val cltDecIv = prekeyIv.copyOfRange(
            MtProtoConstants.PREKEY_LEN,
            MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        )
        val refCltDec = aesCtr(cltDecKey, cltDecIv, ByteArray(64) + msg).copyOfRange(64, 64 + msg.size)
        assertContentEquals(refCltDec, ctx.cltDecryptor.update(msg))

        // Client encrypt: derived from the REVERSED client prekey+iv, and NOT fast-forwarded
        // (no init prefix ever goes back to the client).
        val rev = prekeyIv.reversedArray()
        val refCltEnc = aesCtr(
            sha256(rev.copyOfRange(0, MtProtoConstants.PREKEY_LEN) + secret),
            rev.copyOfRange(MtProtoConstants.PREKEY_LEN, MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN),
            msg
        )
        assertContentEquals(refCltEnc, ctx.cltEncryptor.update(msg))

        // Telegram encrypt: raw relayInit key/iv (no secret hash on the relay leg),
        // fast-forwarded past the init that is itself sent as the first frame.
        val refTgEnc = aesCtr(
            relayInit.copyOfRange(MtProtoConstants.SKIP_LEN, MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN),
            relayInit.copyOfRange(
                MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN,
                MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
            ),
            ByteArray(64) + msg
        ).copyOfRange(64, 64 + msg.size)
        assertContentEquals(refTgEnc, ctx.tgEncryptor.update(msg))

        // Telegram decrypt: REVERSED relayInit prekey+iv, no secret hash, no fast-forward.
        val tgRev = relayInit.copyOfRange(
            MtProtoConstants.SKIP_LEN,
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        ).reversedArray()
        val refTgDec = aesCtr(
            tgRev.copyOfRange(0, MtProtoConstants.KEY_LEN),
            tgRev.copyOfRange(MtProtoConstants.KEY_LEN, MtProtoConstants.KEY_LEN + MtProtoConstants.IV_LEN),
            msg
        )
        assertContentEquals(refTgDec, ctx.tgDecryptor.update(msg))
    }
}
