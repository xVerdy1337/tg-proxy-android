package com.tgwsproxy.vpn

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [PacketUtils] — the userspace IPv4/TCP/UDP parser & builder behind the DPI-desync
 * VPN. Two things matter most here:
 *   1. build↔parse round-trips (a packet we emit must read back identically), and
 *   2. [PacketUtils.isWellFormedIpv4L4] must NEVER let a malformed/truncated packet reach the
 *      L4 accessors — otherwise a single crafted local packet crashes readLoop and tears down
 *      the whole tunnel. We fuzz it with thousands of random/truncated buffers.
 */
class PacketUtilsTest {

    private val SRC = byteArrayOf(10, 0, 0, 2)
    private val DST = byteArrayOf(142.toByte(), 250.toByte(), 1, 78)

    private fun u(b: Byte) = b.toInt() and 0xFF

    // ---- build ↔ parse round trips ----

    @Test
    fun tcpBuildParseRoundTrip() {
        val payload = ByteArray(200) { (it * 7).toByte() }
        val pkt = PacketUtils.buildTcp(
            SRC, 51000, DST, 443,
            seq = 0xEEFF1122L, ack = 0x00A0B0C0L,
            flags = PacketUtils.TcpFlag.PSH or PacketUtils.TcpFlag.ACK,
            window = 64240, payload = payload
        )

        assertTrue(PacketUtils.isWellFormedIpv4L4(pkt))
        assertEquals(4, PacketUtils.ipVersion(pkt))
        assertEquals(PacketUtils.PROTO_TCP, PacketUtils.protocol(pkt))
        assertContentEquals(SRC, PacketUtils.srcIp(pkt))
        assertContentEquals(DST, PacketUtils.dstIp(pkt))
        assertEquals(51000, PacketUtils.srcPort(pkt))
        assertEquals(443, PacketUtils.dstPort(pkt))
        assertEquals(0xEEFF1122L, PacketUtils.tcpSeq(pkt))
        assertEquals(0x00A0B0C0L, PacketUtils.tcpAck(pkt))
        assertEquals(PacketUtils.TcpFlag.PSH or PacketUtils.TcpFlag.ACK, PacketUtils.tcpFlags(pkt))
        assertEquals(64240, PacketUtils.tcpWindow(pkt))
        assertContentEquals(payload, PacketUtils.tcpPayload(pkt))
    }

    @Test
    fun tcpSynWithMssOptionParsesPayloadAfterOptions() {
        val pkt = PacketUtils.buildTcp(
            SRC, 40000, DST, 443,
            seq = 1L, ack = 0L, flags = PacketUtils.TcpFlag.SYN, window = 65535,
            payload = ByteArray(0), mss = 1460
        )
        assertTrue(PacketUtils.isWellFormedIpv4L4(pkt))
        // data offset must account for the 4-byte MSS option (24-byte TCP header)
        assertEquals(24, PacketUtils.tcpDataOffset(pkt))
        assertEquals(0, PacketUtils.tcpPayload(pkt).size)
        assertEquals(1460, (u(pkt[20 + 22]) shl 8) or u(pkt[20 + 23])) // MSS value
    }

    @Test
    fun udpBuildParseRoundTrip() {
        val payload = ByteArray(48) { it.toByte() }
        val pkt = PacketUtils.buildUdp(SRC, 5353, DST, 53, payload)
        assertTrue(PacketUtils.isWellFormedIpv4L4(pkt))
        assertEquals(PacketUtils.PROTO_UDP, PacketUtils.protocol(pkt))
        assertEquals(5353, PacketUtils.srcPort(pkt))
        assertEquals(53, PacketUtils.dstPort(pkt))
        assertContentEquals(payload, PacketUtils.udpPayload(pkt))
    }

    // ---- checksums ----

    @Test
    fun ipv4HeaderChecksumVerifiesToZero() {
        val pkt = PacketUtils.buildUdp(SRC, 1000, DST, 2000, ByteArray(4))
        // Re-summing a header that already carries its checksum yields 0 (one's-complement).
        assertEquals(0, PacketUtils.checksum(pkt, 0, 20))
    }

    // ---- flowKey ----

    @Test
    fun flowKeyDistinguishesTuples() {
        val a = PacketUtils.flowKey(1000, 0x0A000001, 443)
        val b = PacketUtils.flowKey(1001, 0x0A000001, 443)
        val c = PacketUtils.flowKey(1000, 0x0A000002, 443)
        val d = PacketUtils.flowKey(1000, 0x0A000001, 444)
        assertEquals(4, setOf(a, b, c, d).size)
        assertEquals(a, PacketUtils.flowKey(1000, 0x0A000001, 443)) // stable
    }

    @Test
    fun ipToStringFormatsUnsigned() {
        assertEquals("142.250.1.78", PacketUtils.ipToString(DST))
        assertEquals("255.255.255.255", PacketUtils.ipToString(byteArrayOf(-1, -1, -1, -1)))
    }

    // ---- isWellFormedIpv4L4: explicit malformed cases ----

    @Test
    fun wellFormedRejectsMalformed() {
        assertFalse(PacketUtils.isWellFormedIpv4L4(ByteArray(0)))
        assertFalse(PacketUtils.isWellFormedIpv4L4(ByteArray(19)))            // shorter than IPv4 header
        // IPv6 (version nibble 6)
        assertFalse(PacketUtils.isWellFormedIpv4L4(ByteArray(40).also { it[0] = 0x60 }))
        // IHL says 60 bytes but buffer is only 20
        assertFalse(PacketUtils.isWellFormedIpv4L4(ByteArray(20).also { it[0] = 0x4F; it[9] = 6 }))
        // TCP proto but no room for the 20-byte TCP header
        assertFalse(PacketUtils.isWellFormedIpv4L4(ByteArray(30).also { it[0] = 0x45; it[9] = 6 }))
        // UDP proto but no room for the 8-byte UDP header
        assertFalse(PacketUtils.isWellFormedIpv4L4(ByteArray(25).also { it[0] = 0x45; it[9] = 17 }))
        // ICMP (proto 1) — neither TCP nor UDP
        assertFalse(PacketUtils.isWellFormedIpv4L4(ByteArray(40).also { it[0] = 0x45; it[9] = 1 }))
    }

    @Test
    fun wellFormedRejectsTcpWithTinyDataOffset() {
        // valid-looking IPv4+TCP length-wise, but TCP data offset < 5 words (invalid)
        val p = ByteArray(40)
        p[0] = 0x45; p[9] = 6
        p[20 + 12] = (3 shl 4).toByte() // data offset = 3 words = 12 bytes (< 20)
        assertFalse(PacketUtils.isWellFormedIpv4L4(p))
    }

    // ---- isWellFormedIpv4L4: fuzz — must never throw, guards every L4 accessor ----

    @Test
    fun fuzzWellFormedNeverThrowsAndGuardsAccessors() {
        val rnd = Random(0xC0FFEE)
        repeat(50_000) {
            val p = ByteArray(rnd.nextInt(0, 80)) { rnd.nextInt(256).toByte() }
            // Must not throw regardless of content.
            val ok = PacketUtils.isWellFormedIpv4L4(p)
            // If it claims well-formed, every L4 accessor the read loop calls must be safe.
            if (ok) {
                PacketUtils.srcPort(p); PacketUtils.dstPort(p)
                when (PacketUtils.protocol(p)) {
                    PacketUtils.PROTO_TCP -> {
                        PacketUtils.tcpSeq(p); PacketUtils.tcpAck(p); PacketUtils.tcpFlags(p)
                        PacketUtils.tcpWindow(p); PacketUtils.tcpPayload(p)
                    }
                    PacketUtils.PROTO_UDP -> PacketUtils.udpPayload(p)
                }
            }
        }
    }

    @Test
    fun fuzzWithValidIpv4PrefixNeverThrows() {
        // Bias the fuzzer toward "looks like IPv4/TCP/UDP" packets so the accessors are exercised
        // on realistic-but-hostile inputs (random IHL, lengths, data offsets).
        val rnd = Random(42)
        repeat(50_000) {
            val size = rnd.nextInt(20, 100)
            val p = ByteArray(size) { rnd.nextInt(256).toByte() }
            p[0] = ((4 shl 4) or rnd.nextInt(5, 16)).toByte() // version 4, random IHL 5..15
            p[9] = if (rnd.nextBoolean()) 6 else 17           // TCP or UDP
            val ok = PacketUtils.isWellFormedIpv4L4(p)
            if (ok) {
                PacketUtils.srcPort(p); PacketUtils.dstPort(p)
                if (PacketUtils.protocol(p) == PacketUtils.PROTO_TCP) {
                    PacketUtils.tcpPayload(p)
                } else {
                    PacketUtils.udpPayload(p)
                }
            }
        }
    }
}