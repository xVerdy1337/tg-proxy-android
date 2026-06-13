package com.tgwsproxy.proxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Splits the (already relay-encrypted) upstream byte stream into individual MTProto
 * transport packets so each can be sent as its own WebSocket binary frame — which is
 * how Telegram's /apiws endpoint expects the obfuscated2 stream to arrive.
 *
 * It keeps a parallel AES-CTR keystream identical to the relay encryptor (same key/iv,
 * same ZERO_64 fast-forward) so it can recover the plaintext lengths from the ciphertext
 * and cut the cipher buffer at packet boundaries.
 */
class MsgSplitter(relayInit: ByteArray, private val protoInt: Long) {

    private val dec: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        val key = relayInit.copyOfRange(MtProtoConstants.SKIP_LEN, MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN)
        val iv = relayInit.copyOfRange(
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN,
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        )
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    private val cipherBuf = ArrayList<Byte>()
    private val plainBuf = ArrayList<Byte>()
    private var disabled = false

    init {
        // Mirror the relay encryptor's ZERO_64 fast-forward so keystreams align.
        dec.update(MtProtoConstants.ZERO_64)
    }

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        for (b in chunk) cipherBuf.add(b)
        val plain = dec.update(chunk)
        for (b in plain) plainBuf.add(b)

        val parts = ArrayList<ByteArray>()
        var offset = 0
        val bufLen = cipherBuf.size
        while (offset < bufLen) {
            val packetLen = nextPacketLen(offset, bufLen - offset) ?: break
            if (packetLen <= 0) {
                // Unknown framing -> stop splitting, flush the rest as one frame.
                parts.add(sliceCipher(offset, bufLen))
                offset = bufLen
                disabled = true
                break
            }
            parts.add(sliceCipher(offset, offset + packetLen))
            offset += packetLen
        }

        if (offset > 0) {
            // Drop consumed prefix from both buffers.
            repeat(offset) {
                cipherBuf.removeAt(0)
                plainBuf.removeAt(0)
            }
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = sliceCipher(0, cipherBuf.size)
        cipherBuf.clear()
        plainBuf.clear()
        return listOf(tail)
    }

    private fun sliceCipher(from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        for (i in from until to) out[i - from] = cipherBuf[i]
        return out
    }

    private fun nextPacketLen(offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        return when (protoInt) {
            MtProtoConstants.PROTO_ABRIDGED_INT -> nextAbridgedLen(offset, avail)
            MtProtoConstants.PROTO_INTERMEDIATE_INT,
            MtProtoConstants.PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediateLen(offset, avail)
            else -> 0
        }
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun nextAbridgedLen(offset: Int, avail: Int): Int? {
        val first = u(plainBuf[offset])
        val payloadLen: Int
        val headerLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) return null
            payloadLen = (u(plainBuf[offset + 1]) or
                    (u(plainBuf[offset + 2]) shl 8) or
                    (u(plainBuf[offset + 3]) shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }

    private fun nextIntermediateLen(offset: Int, avail: Int): Int? {
        if (avail < 4) return null
        val payloadLen = ((u(plainBuf[offset]) or
                (u(plainBuf[offset + 1]) shl 8) or
                (u(plainBuf[offset + 2]) shl 16) or
                (u(plainBuf[offset + 3]) shl 24)) and 0x7FFFFFFF)
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }
}
