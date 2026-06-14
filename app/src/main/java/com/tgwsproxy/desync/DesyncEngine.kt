package com.tgwsproxy.desync

/**
 * Pure, dependency-free DPI-desync core (ByeDPI-style) — the heart of the "unblock YouTube /
 * Discord / Instagram" feature.
 *
 * It does NOT tunnel traffic anywhere. It manipulates the first outbound TLS bytes of a
 * connection (the ClientHello) so a SNI-based DPI (Russian TSPU) can't cleanly read the
 * server name and therefore can't throttle/block the flow. The actual server still receives a
 * valid TLS handshake — we only change how the bytes are framed/ordered on the wire.
 *
 * This class is deliberately Android-free so it can be unit-tested in isolation. It will be
 * driven by the VpnService routing layer (next increment): for each new TCP flow, the layer
 * feeds the first client->server payload here and sends back the (possibly re-framed) bytes.
 *
 * Methods implemented:
 *  - SPLIT    : cut the TCP payload into two segments inside the SNI → two write()s.
 *  - TLSREC   : re-fragment the single TLS record into two valid TLS records at the SNI.
 *  - DISORDER : like SPLIT but the second segment is emitted first (caller sends out of order).
 *
 * FAKE (a poisoning packet with a low TTL/bad checksum) needs raw-socket / TTL control and is
 * therefore handled at the routing layer, not here.
 */
object DesyncEngine {

    enum class Method { SPLIT, TLSREC, DISORDER }

    private const val TLS_RECORD_HANDSHAKE = 0x16
    private const val TLS_HANDSHAKE_CLIENT_HELLO = 0x01
    private const val EXT_SERVER_NAME = 0x0000
    private const val SNI_TYPE_HOSTNAME = 0x00

    /** Result of desyncing one payload: an ordered list of byte chunks to write to the socket. */
    data class Plan(val chunks: List<ByteArray>)

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    /** True if [data] looks like a TLS ClientHello record (the only thing worth desyncing). */
    fun isClientHello(data: ByteArray): Boolean {
        if (data.size < 6) return false
        if (u8(data, 0) != TLS_RECORD_HANDSHAKE) return false
        // record length sanity
        val recLen = u16(data, 3)
        if (recLen < 4) return false
        return u8(data, 5) == TLS_HANDSHAKE_CLIENT_HELLO
    }

    /**
     * Find the absolute byte offset, within the whole record [data], of the SNI hostname's
     * first byte. Returns null if the payload isn't a ClientHello with an SNI extension.
     *
     * Layout walked: TLS record header(5) -> Handshake header(4) -> ClientHello{ version(2),
     * random(32), session_id, cipher_suites, compression_methods, extensions } -> ext
     * server_name(0x0000) -> server_name_list -> entry{ type(1), len(2), host... }.
     */
    fun sniHostnameOffset(data: ByteArray): Int? {
        try {
            if (!isClientHello(data)) return null
            var p = 5            // skip TLS record header
            // Handshake header: type(1) + length(3)
            p += 4
            // ClientHello fixed: client_version(2) + random(32)
            p += 2 + 32
            // session_id
            val sidLen = u8(data, p); p += 1 + sidLen
            // cipher_suites
            val csLen = u16(data, p); p += 2 + csLen
            // compression_methods
            val cmLen = u8(data, p); p += 1 + cmLen
            // extensions
            if (p + 2 > data.size) return null
            val extTotal = u16(data, p); p += 2
            val extEnd = minOf(p + extTotal, data.size)
            while (p + 4 <= extEnd) {
                val type = u16(data, p)
                val len = u16(data, p + 2)
                val body = p + 4
                if (type == EXT_SERVER_NAME) {
                    // server_name_list length(2)
                    var q = body + 2
                    // entry: name_type(1) + name_len(2) + host
                    if (q + 3 > data.size) return null
                    val nameType = u8(data, q)
                    val nameLen = u16(data, q + 1)
                    if (nameType != SNI_TYPE_HOSTNAME) return null
                    val hostStart = q + 3
                    if (hostStart + nameLen > data.size) return null
                    return hostStart
                }
                p = body + len
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Build the write plan for an outbound first-payload. If [data] isn't a ClientHello or has
     * no SNI, it's returned unchanged (single chunk) so non-TLS / non-SNI traffic is untouched.
     */
    fun plan(data: ByteArray, method: Method): Plan {
        val hostOff = sniHostnameOffset(data) ?: return Plan(listOf(data))
        // Split a couple of bytes into the hostname so the SNI string itself is broken across
        // the boundary (this is what defeats naive SNI matching).
        val splitAt = (hostOff + 1).coerceIn(1, data.size - 1)
        return when (method) {
            Method.SPLIT -> Plan(listOf(data.copyOfRange(0, splitAt), data.copyOfRange(splitAt, data.size)))
            Method.DISORDER -> Plan(listOf(data.copyOfRange(splitAt, data.size), data.copyOfRange(0, splitAt)))
            Method.TLSREC -> Plan(tlsRecordFragments(data, splitAt))
        }
    }

    /**
     * Re-fragment a single TLS record into two *valid* TLS records split at absolute offset
     * [splitAt] (which must fall inside the record payload). The handshake message bytes are
     * untouched — only the record framing changes — so the server reassembles normally while a
     * record-by-record DPI sees the SNI cut in half.
     */
    fun tlsRecordFragments(data: ByteArray, splitAt: Int): List<ByteArray> {
        // Need at least the 5-byte record header + something on each side.
        if (data.size < 6 || splitAt <= 5 || splitAt >= data.size) return listOf(data)
        val type = data[0]
        val verMajor = data[1]
        val verMinor = data[2]
        val payloadStart = 5
        val firstLen = splitAt - payloadStart
        val secondLen = data.size - splitAt
        if (firstLen <= 0 || secondLen <= 0) return listOf(data)

        fun header(len: Int): ByteArray = byteArrayOf(
            type, verMajor, verMinor,
            ((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte()
        )

        val rec1 = header(firstLen) + data.copyOfRange(payloadStart, splitAt)
        val rec2 = header(secondLen) + data.copyOfRange(splitAt, data.size)
        // Two records can be sent in one or two writes; we emit them as one chunk by default,
        // the routing layer may also split the TCP segment for extra effect.
        return listOf(rec1, rec2)
    }
}
