package com.tgwsproxy.vpn

/**
 * Minimal IPv4 / TCP / UDP packet parsing & building for the userspace tunnel.
 *
 * The VpnService TUN gives us raw IP packets (no Ethernet framing). We only need IPv4 here —
 * IPv6 is dropped upstream to force apps onto IPv4 where the DPI-desync applies. Everything is
 * big-endian (network order). Helpers are intentionally allocation-light and dependency-free.
 */
object PacketUtils {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    fun ipVersion(p: ByteArray): Int = if (p.isEmpty()) 0 else (p[0].toInt() ushr 4) and 0x0F

    // ---- IPv4 header accessors (offsets within the whole packet) ----

    fun ihl(p: ByteArray): Int = (p[0].toInt() and 0x0F) * 4
    fun totalLength(p: ByteArray): Int = u16(p, 2)
    fun protocol(p: ByteArray): Int = p[9].toInt() and 0xFF
    fun srcIp(p: ByteArray): ByteArray = p.copyOfRange(12, 16)
    fun dstIp(p: ByteArray): ByteArray = p.copyOfRange(16, 20)
    fun srcIpInt(p: ByteArray): Int = beInt(p, 12)
    fun dstIpInt(p: ByteArray): Int = beInt(p, 16)

    // ---- L4 accessors (need the IPv4 header length) ----

    fun srcPort(p: ByteArray): Int = u16(p, ihl(p))
    fun dstPort(p: ByteArray): Int = u16(p, ihl(p) + 2)

    // TCP
    fun tcpSeq(p: ByteArray): Long = beUInt(p, ihl(p) + 4)
    fun tcpAck(p: ByteArray): Long = beUInt(p, ihl(p) + 8)
    fun tcpDataOffset(p: ByteArray): Int {
        val l4 = ihl(p)
        return ((p[l4 + 12].toInt() ushr 4) and 0x0F) * 4
    }
    fun tcpFlags(p: ByteArray): Int = p[ihl(p) + 13].toInt() and 0xFF
    fun tcpWindow(p: ByteArray): Int = u16(p, ihl(p) + 14)

    fun tcpPayload(p: ByteArray): ByteArray {
        val l4 = ihl(p)
        val start = l4 + tcpDataOffset(p)
        val end = totalLength(p)
        if (start >= end || end > p.size) return ByteArray(0)
        return p.copyOfRange(start, end)
    }

    fun udpPayload(p: ByteArray): ByteArray {
        val l4 = ihl(p)
        val len = u16(p, l4 + 4) // UDP length field (header + data)
        val start = l4 + 8
        val end = minOf(l4 + len, totalLength(p), p.size)
        if (start >= end) return ByteArray(0)
        return p.copyOfRange(start, end)
    }

    object TcpFlag {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
    }

    // ---- builders ----

    /**
     * Build a complete IPv4 + TCP packet (no options). [src]/[dst] are 4-byte addresses,
     * [payload] may be empty (pure ACK / control segment).
     */
    fun buildTcp(
        src: ByteArray, srcPort: Int,
        dst: ByteArray, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int,
        payload: ByteArray
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val totalLen = 20 + tcpLen
        val out = ByteArray(totalLen)

        // IPv4 header
        out[0] = 0x45 // version 4, IHL 5
        out[1] = 0
        putU16(out, 2, totalLen)
        putU16(out, 4, 0)          // identification
        putU16(out, 6, 0x4000)     // don't fragment
        out[8] = 64                // TTL
        out[9] = PROTO_TCP.toByte()
        // checksum (10..11) computed below
        System.arraycopy(src, 0, out, 12, 4)
        System.arraycopy(dst, 0, out, 16, 4)
        putU16(out, 10, checksum(out, 0, 20))

        // TCP header
        val t = 20
        putU16(out, t, srcPort)
        putU16(out, t + 2, dstPort)
        putU32(out, t + 4, seq)
        putU32(out, t + 8, ack)
        out[t + 12] = (5 shl 4).toByte() // data offset 5 words, no options
        out[t + 13] = flags.toByte()
        putU16(out, t + 14, window)
        // checksum (t+16) below, urgent ptr 0
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, t + 20, payload.size)
        putU16(out, t + 16, l4Checksum(out, src, dst, PROTO_TCP, t, tcpLen))

        return out
    }

    fun buildUdp(
        src: ByteArray, srcPort: Int,
        dst: ByteArray, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)

        out[0] = 0x45
        out[1] = 0
        putU16(out, 2, totalLen)
        putU16(out, 6, 0x4000)
        out[8] = 64
        out[9] = PROTO_UDP.toByte()
        System.arraycopy(src, 0, out, 12, 4)
        System.arraycopy(dst, 0, out, 16, 4)
        putU16(out, 10, checksum(out, 0, 20))

        val u = 20
        putU16(out, u, srcPort)
        putU16(out, u + 2, dstPort)
        putU16(out, u + 4, udpLen)
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, u + 8, payload.size)
        putU16(out, u + 6, l4Checksum(out, src, dst, PROTO_UDP, u, udpLen))

        return out
    }

    // ---- checksums ----

    /** Standard 16-bit one's-complement checksum over [off, off+len). */
    fun checksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** TCP/UDP checksum incl. the IPv4 pseudo-header. l4Off = byte offset of the L4 header. */
    private fun l4Checksum(packet: ByteArray, src: ByteArray, dst: ByteArray, proto: Int, l4Off: Int, l4Len: Int): Int {
        var sum = 0L
        // pseudo-header: src(4) + dst(4) + zero + proto + l4Len
        sum += ((src[0].toInt() and 0xFF) shl 8) or (src[1].toInt() and 0xFF)
        sum += ((src[2].toInt() and 0xFF) shl 8) or (src[3].toInt() and 0xFF)
        sum += ((dst[0].toInt() and 0xFF) shl 8) or (dst[1].toInt() and 0xFF)
        sum += ((dst[2].toInt() and 0xFF) shl 8) or (dst[3].toInt() and 0xFF)
        sum += proto.toLong()
        sum += l4Len.toLong()
        var i = l4Off
        val end = l4Off + l4Len
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        var cs = (sum.inv() and 0xFFFF).toInt()
        if (cs == 0) cs = 0xFFFF // UDP: 0 means "no checksum", avoid it
        return cs
    }

    // ---- little numeric helpers ----

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun beInt(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
    private fun beUInt(b: ByteArray, i: Int): Long = beInt(b, i).toLong() and 0xFFFFFFFFL

    private fun putU16(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v ushr 8) and 0xFF).toByte()
        b[i + 1] = (v and 0xFF).toByte()
    }
    private fun putU32(b: ByteArray, i: Int, v: Long) {
        b[i] = ((v ushr 24) and 0xFF).toByte()
        b[i + 1] = ((v ushr 16) and 0xFF).toByte()
        b[i + 2] = ((v ushr 8) and 0xFF).toByte()
        b[i + 3] = (v and 0xFF).toByte()
    }

    fun ipToString(ip: ByteArray): String =
        "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"

    /** 4-byte key packed into a long for NAT maps: (ip<<16)|port is not unique enough; use full tuple hash. */
    fun flowKey(srcPort: Int, dstIp: Int, dstPort: Int): Long =
        ((srcPort.toLong() and 0xFFFF) shl 48) or ((dstPort.toLong() and 0xFFFF) shl 32) or (dstIp.toLong() and 0xFFFFFFFFL)
}
