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
}
