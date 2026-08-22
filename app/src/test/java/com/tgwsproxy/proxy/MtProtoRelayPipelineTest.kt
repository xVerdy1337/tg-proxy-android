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
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Drives all four relay ciphers against a stand-in client and a stand-in Telegram, because the
 * pieces being right individually is not the property that matters — them staying in step is.
 *
 * The relay runs two independent obfuscated2 legs (client<->proxy, proxy<->Telegram) with four
 * AES-CTR keystreams, and two of them are fast-forwarded 64 bytes while the other two are not
 * (the init header exists on one side of each leg only). A keystream that starts one byte off, or
 * that is re-initialised per chunk, still encrypts and decrypts perfectly well on its own — it
 * just produces garbage at the far end. Only both ends of both legs, driven together, can tell.
 *
 * Everything here is javax.crypto over ByteArrays: no sockets, no Android, no network. The two
 * stand-ins implement obfuscated2 from the spec side rather than by calling production helpers, so
 * a mistake copied into [MtProtoHandshake] cannot cancel itself out.
 */
class MtProtoRelayPipelineTest {

    private val secret = ByteArray(16) { (it * 11 + 5).toByte() }

    /** Chunk sizes straddling the 16-byte AES block: a per-chunk cipher reset only shows up here. */
    private val chunkSizes = listOf(1, 15, 16, 17, 63, 64, 65, 100, 4096)

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun ctr(key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }

    private fun leShort(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private fun intHeader(len: Int) = byteArrayOf(
        (len and 0xFF).toByte(),
        ((len ushr 8) and 0xFF).toByte(),
        ((len ushr 16) and 0xFF).toByte(),
        ((len ushr 24) and 0xFF).toByte()
    )

    // === Stand-in client ===

    /** The 64 bytes a Telegram client puts on the wire, plus the two ciphers it keeps afterwards. */
    private class Client(val initBytes: ByteArray, val enc: Cipher, val dec: Cipher)

    /**
     * Build a client the way the obfuscated2 spec does: pick 64 bytes, plant the proto tag and DC
     * index at 56..61, encrypt the whole thing with SHA256(prekey + secret), then ship bytes 0..55
     * in the clear and 56..63 encrypted. The cipher that did it is kept for payload, which is why
     * the client's keystream is already 64 bytes in — the proxy's `cltDecryptor` has to match that.
     */
    private fun client(protoTag: ByteArray, dcIdx: Int): Client {
        val p = ByteArray(MtProtoConstants.HANDSHAKE_LEN) { (it * 9 + 1).toByte() }
        System.arraycopy(protoTag, 0, p, MtProtoConstants.PROTO_TAG_POS, 4)
        System.arraycopy(leShort(dcIdx), 0, p, MtProtoConstants.DC_IDX_POS, 2)

        val enc = ctr(sha256(p.copyOfRange(8, 40) + secret), p.copyOfRange(40, 56))
        val encInit = enc.update(p)
        val wire = p.copyOfRange(0, 56) + encInit.copyOfRange(56, 64)

        // Data coming back is keyed off the REVERSED prekey+iv, and carries no init header — so
        // this cipher starts at keystream position 0.
        val rev = p.copyOfRange(8, 56).reversedArray()
        val dec = ctr(sha256(rev.copyOfRange(0, 32) + secret), rev.copyOfRange(32, 48))

        return Client(wire, enc, dec)
    }

    // === Stand-in Telegram ===

    /** The Telegram end of the relay leg: obfuscated2 with RAW relayInit keys — no secret hash. */
    private class Telegram(val initPlain: ByteArray, val enc: Cipher, val dec: Cipher)

    private fun telegram(relayInit: ByteArray): Telegram {
        val dec = ctr(relayInit.copyOfRange(8, 40), relayInit.copyOfRange(40, 56))
        // Telegram consumes the 64-byte init off this very keystream before any payload arrives,
        // which is what the proxy's ZERO_64 fast-forward on `tgEncryptor` is mirroring.
        val initPlain = dec.update(relayInit)

        val rev = relayInit.copyOfRange(8, 56).reversedArray()
        val enc = ctr(rev.copyOfRange(0, 32), rev.copyOfRange(32, 48))

        return Telegram(initPlain, enc, dec)
    }

    // === The load-bearing platform assumption ===

    @Test
    fun aesCtrUpdateEmitsEveryInputByteImmediately() {
        // The relay, and MsgSplitter's parallel keystream in particular, assume update() returns
        // one output byte per input byte with nothing held back. A provider that buffered partial
        // blocks (as CBC/ECB do) would desynchronise cipherBuf from plainBuf and corrupt framing
        // rather than fail — so pin the assumption here, where the diagnosis is obvious.
        val c = ctr(ByteArray(32), ByteArray(16))
        assertEquals(1, c.update(ByteArray(1)).size)
        assertEquals(7, c.update(ByteArray(7)).size)
        assertEquals(64, c.update(ByteArray(64)).size)
    }

    // === generateRelayInit ===

    @Test
    fun generatedRelayInitDecodesToItsProtoTagAndDcOnTheTelegramSide() {
        // generateRelayInit does not encrypt its tail the ordinary way: it recovers the keystream
        // from a throwaway encryption of the random block and XORs the tail in by hand. If that
        // splice is off, Telegram reads a bad tag and drops the session with no diagnostic.
        val tag = MtProtoConstants.PROTO_TAG_INTERMEDIATE
        val tg = telegram(MtProtoHandshake.generateRelayInit(tag, 2))

        assertContentEquals(tag, tg.initPlain.copyOfRange(56, 60))
        assertContentEquals(leShort(2), tg.initPlain.copyOfRange(60, 62))
    }

    @Test
    fun generatedRelayInitCarriesAMediaDcAsANegativeIndex() {
        val tag = MtProtoConstants.PROTO_TAG_SECURE
        val tg = telegram(MtProtoHandshake.generateRelayInit(tag, -4))

        assertContentEquals(tag, tg.initPlain.copyOfRange(56, 60))
        val dc = ByteBuffer.wrap(tg.initPlain.copyOfRange(60, 62)).order(ByteOrder.LITTLE_ENDIAN).short
        assertEquals(-4, dc.toInt())
    }

    @Test
    fun everyGeneratedRelayInitAvoidsTheProtocolDetectionPrefixes() {
        // The first 4 bytes must not collide with an HTTP verb, another proto tag or a TLS record
        // header, or middleboxes classify the stream. It is a rejection loop over random bytes, so
        // sample it rather than trusting one draw.
        val forbidden = listOf(
            byteArrayOf(0x48, 0x45, 0x41, 0x44), byteArrayOf(0x50, 0x4F, 0x53, 0x54),
            byteArrayOf(0x47, 0x45, 0x54, 0x20), byteArrayOf(0x16, 0x03, 0x01, 0x02),
            MtProtoConstants.PROTO_TAG_INTERMEDIATE, MtProtoConstants.PROTO_TAG_SECURE
        )
        repeat(200) {
            val init = MtProtoHandshake.generateRelayInit(MtProtoConstants.PROTO_TAG_ABRIDGED, 2)
            assertEquals(MtProtoConstants.HANDSHAKE_LEN, init.size)
            assertFalse(init[0] == 0xEF.toByte(), "first byte must not look like an abridged tag")
            val first4 = init.copyOfRange(0, 4)
            for (bad in forbidden) {
                assertFalse(first4.contentEquals(bad), "generated a forbidden prefix")
            }
            assertFalse(
                init.copyOfRange(4, 8).contentEquals(ByteArray(4)),
                "bytes 4..7 must not be all zero"
            )
        }
    }

    // === Both legs, driven together ===

    @Test
    fun clientPayloadReachesTelegramUnchangedAcrossUnalignedChunks() {
        val c = client(MtProtoConstants.PROTO_TAG_INTERMEDIATE, 2)
        val res = MtProtoHandshake.tryHandshake(c.initBytes, secret)!!
        assertEquals(2, res.dcId)
        assertFalse(res.isMedia)

        val relayInit = MtProtoHandshake.generateRelayInit(res.protoTag, 2)
        val ctx = MtProtoHandshake.buildCryptoContext(res.clientDecPrekeyIv, secret, relayInit)
        val tg = telegram(relayInit)

        for ((n, size) in chunkSizes.withIndex()) {
            val payload = ByteArray(size) { ((it * 31) + n).toByte() }

            val onWire = c.enc.update(payload)
            val atProxy = ctx.cltDecryptor.update(onWire)
            assertContentEquals(payload, atProxy, "proxy misread client chunk $n of $size bytes")

            val reencrypted = ctx.tgEncryptor.update(atProxy)
            val atTelegram = tg.dec.update(reencrypted)
            assertContentEquals(payload, atTelegram, "telegram misread chunk $n of $size bytes")
        }
    }

    @Test
    fun telegramPayloadReachesTheClientUnchangedAcrossUnalignedChunks() {
        val c = client(MtProtoConstants.PROTO_TAG_INTERMEDIATE, 2)
        val res = MtProtoHandshake.tryHandshake(c.initBytes, secret)!!
        val relayInit = MtProtoHandshake.generateRelayInit(res.protoTag, 2)
        val ctx = MtProtoHandshake.buildCryptoContext(res.clientDecPrekeyIv, secret, relayInit)
        val tg = telegram(relayInit)

        for ((n, size) in chunkSizes.withIndex()) {
            val payload = ByteArray(size) { ((it * 17) + n).toByte() }

            val onWire = tg.enc.update(payload)
            val atProxy = ctx.tgDecryptor.update(onWire)
            assertContentEquals(payload, atProxy, "proxy misread telegram chunk $n of $size bytes")

            val toClient = ctx.cltEncryptor.update(atProxy)
            val atClient = c.dec.update(toClient)
            assertContentEquals(payload, atClient, "client misread chunk $n of $size bytes")
        }
    }

    @Test
    fun theAbridgedAndSecureProtocolsRelayJustAsWell() {
        // The proto tag picks the framing, not the crypto, but it rides through the same keystream
        // splice — so each tag the handshake accepts needs to survive a round trip.
        for (tag in listOf(MtProtoConstants.PROTO_TAG_ABRIDGED, MtProtoConstants.PROTO_TAG_SECURE)) {
            val c = client(tag, 3)
            val res = MtProtoHandshake.tryHandshake(c.initBytes, secret)!!
            assertEquals(3, res.dcId)
            assertContentEquals(tag, res.protoTag)

            val relayInit = MtProtoHandshake.generateRelayInit(res.protoTag, 3)
            val ctx = MtProtoHandshake.buildCryptoContext(res.clientDecPrekeyIv, secret, relayInit)
            val tg = telegram(relayInit)
            assertContentEquals(tag, tg.initPlain.copyOfRange(56, 60))

            val payload = ByteArray(300) { (it * 13).toByte() }
            val atTelegram = tg.dec.update(ctx.tgEncryptor.update(ctx.cltDecryptor.update(c.enc.update(payload))))
            assertContentEquals(payload, atTelegram)
        }
    }

    @Test
    fun aWrongSecretIsRejectedRatherThanRelayedAsGarbage() {
        val c = client(MtProtoConstants.PROTO_TAG_INTERMEDIATE, 2)
        val wrong = ByteArray(16) { (it * 11 + 6).toByte() }
        assertNull(MtProtoHandshake.tryHandshake(c.initBytes, wrong))
    }

    // === The splitter, in the pipeline it actually runs in ===

    @Test
    fun splitterCutsTheExactCiphertextTelegramWillDecrypt() {
        // MsgSplitter keeps its own AES-CTR keystream alongside tgEncryptor so it can read packet
        // lengths back out of ciphertext. Tested standalone it only proves it agrees with itself;
        // here it has to agree with the encryptor the relay really used, and the frames it emits
        // have to reassemble into a stream Telegram can still decrypt.
        val c = client(MtProtoConstants.PROTO_TAG_INTERMEDIATE, 2)
        val res = MtProtoHandshake.tryHandshake(c.initBytes, secret)!!
        val relayInit = MtProtoHandshake.generateRelayInit(res.protoTag, 2)
        val ctx = MtProtoHandshake.buildCryptoContext(res.clientDecPrekeyIv, secret, relayInit)
        val tg = telegram(relayInit)
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)

        val framed = intHeader(8) + ByteArray(8) { 1 } +
                intHeader(20) + ByteArray(20) { 2 } +
                intHeader(4) + ByteArray(4) { 3 }

        val reencrypted = ctx.tgEncryptor.update(ctx.cltDecryptor.update(c.enc.update(framed)))
        val frames = splitter.split(reencrypted)

        assertEquals(3, frames.size, "one WebSocket frame per MTProto packet")
        assertEquals(12, frames[0].size)
        assertEquals(24, frames[1].size)
        assertEquals(8, frames[2].size)

        val rejoined = frames.reduce { a, b -> a + b }
        assertContentEquals(reencrypted, rejoined, "framing must not alter the cipher stream")
        assertContentEquals(framed, tg.dec.update(rejoined), "telegram must still decrypt it")
        assertEquals(0, splitter.flush().size, "nothing should be left buffered")
    }

    @Test
    fun splitterStaysAlignedWhenTheClientDribblesBytes() {
        // Telegram writes into a 256 KB loopback buffer, so a packet can reach us in arbitrary
        // pieces. Every chunk boundary is a chance for the splitter's keystream to drift from the
        // encryptor's; feeding one byte at a time maximises those chances.
        val c = client(MtProtoConstants.PROTO_TAG_INTERMEDIATE, 2)
        val res = MtProtoHandshake.tryHandshake(c.initBytes, secret)!!
        val relayInit = MtProtoHandshake.generateRelayInit(res.protoTag, 2)
        val ctx = MtProtoHandshake.buildCryptoContext(res.clientDecPrekeyIv, secret, relayInit)
        val tg = telegram(relayInit)
        val splitter = MsgSplitter(relayInit, MtProtoConstants.PROTO_INTERMEDIATE_INT)

        val framed = intHeader(16) + ByteArray(16) { 4 } + intHeader(12) + ByteArray(12) { 5 }
        val onWire = c.enc.update(framed)

        val frames = ArrayList<ByteArray>()
        for (b in onWire) {
            val one = ctx.tgEncryptor.update(ctx.cltDecryptor.update(byteArrayOf(b)))
            frames.addAll(splitter.split(one))
        }

        assertEquals(2, frames.size)
        assertEquals(20, frames[0].size)
        assertEquals(16, frames[1].size)
        assertContentEquals(framed, tg.dec.update(frames.reduce { a, b -> a + b }))
    }
}
