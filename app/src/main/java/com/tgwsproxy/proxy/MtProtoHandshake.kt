package com.tgwsproxy.proxy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MtProtoConstants {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    val PROTO_TAG_ABRIDGED = byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte())

    const val PROTO_ABRIDGED_INT = 0xEFEFEFEF
    const val PROTO_INTERMEDIATE_INT = 0xEEEEEEEE
    const val PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDD

    val ZERO_64 = ByteArray(64) { 0 }
}

data class HandshakeResult(
    val dcId: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    val clientDecPrekeyIv: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HandshakeResult
        return dcId == other.dcId && isMedia == other.isMedia &&
                protoTag.contentEquals(other.protoTag) &&
                clientDecPrekeyIv.contentEquals(other.clientDecPrekeyIv)
    }

    override fun hashCode(): Int {
        var result = dcId
        result = 31 * result + isMedia.hashCode()
        result = 31 * result + protoTag.contentHashCode()
        result = 31 * result + clientDecPrekeyIv.contentHashCode()
        return result
    }
}

object MtProtoHandshake {

    private val secureRandom = SecureRandom()

    fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeResult? {
        require(handshake.size == MtProtoConstants.HANDSHAKE_LEN)

        val decPrekeyAndIv = handshake.copyOfRange(
            MtProtoConstants.SKIP_LEN,
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        )
        val decPrekey = decPrekeyAndIv.copyOfRange(0, MtProtoConstants.PREKEY_LEN)
        val decIv = decPrekeyAndIv.copyOfRange(MtProtoConstants.PREKEY_LEN, MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN)

        val decKey = sha256(decPrekey + secret)
        val decrypted = aesCtrEncrypt(decKey, decIv, handshake)

        val protoTag = decrypted.copyOfRange(MtProtoConstants.PROTO_TAG_POS, MtProtoConstants.PROTO_TAG_POS + 4)

        if (!protoTag.contentEquals(MtProtoConstants.PROTO_TAG_ABRIDGED) &&
            !protoTag.contentEquals(MtProtoConstants.PROTO_TAG_INTERMEDIATE) &&
            !protoTag.contentEquals(MtProtoConstants.PROTO_TAG_SECURE)) {
            return null
        }

        val dcIdx = ByteBuffer
            .wrap(decrypted.copyOfRange(MtProtoConstants.DC_IDX_POS, MtProtoConstants.DC_IDX_POS + 2))
            .order(ByteOrder.LITTLE_ENDIAN)
            .short

        val dcId = kotlin.math.abs(dcIdx.toInt())
        val isMedia = dcIdx < 0

        return HandshakeResult(dcId, isMedia, protoTag, decPrekeyAndIv)
    }

    fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        while (true) {
            val rnd = ByteArray(MtProtoConstants.HANDSHAKE_LEN)
            secureRandom.nextBytes(rnd)

            if (rnd[0] in listOf(0xEF.toByte())) continue
            val first4 = rnd.copyOfRange(0, 4)
            if (first4.contentEquals(byteArrayOf(0x48, 0x45, 0x41, 0x44)) ||
                first4.contentEquals(byteArrayOf(0x50, 0x4F, 0x53, 0x54)) ||
                first4.contentEquals(byteArrayOf(0x47, 0x45, 0x54, 0x20)) ||
                first4.contentEquals(MtProtoConstants.PROTO_TAG_INTERMEDIATE) ||
                first4.contentEquals(MtProtoConstants.PROTO_TAG_SECURE) ||
                first4.contentEquals(byteArrayOf(0x16, 0x03, 0x01, 0x02))) continue
            if (rnd.copyOfRange(4, 8).contentEquals(ByteArray(4) { 0 })) continue

            val encKey = rnd.copyOfRange(MtProtoConstants.SKIP_LEN, MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN)
            val encIv = rnd.copyOfRange(
                MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN,
                MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
            )

            val encryptor = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(encIv))
            }

            val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dcIdx.toShort()).array()
            val tailPlain = protoTag + dcBytes + ByteArray(2).apply { secureRandom.nextBytes(this) }

            val encryptedFull = encryptor.update(rnd)
            val keystreamTail = ByteArray(8) { i ->
                (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt()).toByte()
            }
            val encryptedTail = ByteArray(8) { i ->
                (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
            }

            val result = rnd.copyOf()
            System.arraycopy(encryptedTail, 0, result, MtProtoConstants.PROTO_TAG_POS, 8)
            return result
        }
    }

    fun buildCryptoContext(
        clientDecPrekeyIv: ByteArray,
        secret: ByteArray,
        relayInit: ByteArray
    ): CryptoContext {
        // Client decrypt: key = SHA256(prekey + secret), iv from handshake
        val cltDecPrekey = clientDecPrekeyIv.copyOfRange(0, MtProtoConstants.PREKEY_LEN)
        val cltDecIv = clientDecPrekeyIv.copyOfRange(MtProtoConstants.PREKEY_LEN, MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN)
        val cltDecKey = sha256(cltDecPrekey + secret)

        val cltEncPrekeyIv = clientDecPrekeyIv.reversedArray()
        val cltEncKey = sha256(cltEncPrekeyIv.copyOfRange(0, MtProtoConstants.PREKEY_LEN) + secret)
        val cltEncIv = cltEncPrekeyIv.copyOfRange(MtProtoConstants.PREKEY_LEN, MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN)

        val cltDecryptor = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cltDecKey, "AES"), IvParameterSpec(cltDecIv))
        }
        cltDecryptor.update(MtProtoConstants.ZERO_64)

        val cltEncryptor = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cltEncKey, "AES"), IvParameterSpec(cltEncIv))
        }

        // Relay side
        val relayEncKey = relayInit.copyOfRange(MtProtoConstants.SKIP_LEN, MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN)
        val relayEncIv = relayInit.copyOfRange(
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN,
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        )

        val relayDecPrekeyIv = relayInit.copyOfRange(
            MtProtoConstants.SKIP_LEN,
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        ).reversedArray()
        val relayDecKey = relayDecPrekeyIv.copyOfRange(0, MtProtoConstants.KEY_LEN)
        val relayDecIv = relayDecPrekeyIv.copyOfRange(MtProtoConstants.KEY_LEN, MtProtoConstants.KEY_LEN + MtProtoConstants.IV_LEN)

        val tgEncryptor = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(relayEncKey, "AES"), IvParameterSpec(relayEncIv))
        }
        tgEncryptor.update(MtProtoConstants.ZERO_64)

        val tgDecryptor = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(relayDecKey, "AES"), IvParameterSpec(relayDecIv))
        }

        return CryptoContext(cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
    }

    fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val actualDc = if (dc == 203) 2 else dc
        return if (isMedia) {
            listOf("kws${actualDc}-1.web.telegram.org", "kws${actualDc}.web.telegram.org")
        } else {
            listOf("kws${actualDc}.web.telegram.org", "kws${actualDc}-1.web.telegram.org")
        }
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun aesCtrEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.update(data)
    }
}

data class CryptoContext(
    val cltDecryptor: Cipher,  // decrypt from client
    val cltEncryptor: Cipher,  // encrypt to client
    val tgEncryptor: Cipher,   // encrypt to telegram
    val tgDecryptor: Cipher    // decrypt from telegram
)
