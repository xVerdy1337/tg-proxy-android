package com.tgwsproxy.vpn

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TcpConnectionTest {

    @Test
    fun socksConnectConsumesTheEntireSuccessReplyWhenSkipMakesNoProgress() {
        val response = byteArrayOf(
            0x05, 0x00,                         // greeting: SOCKS5, no authentication
            0x05, 0x00, 0x00, 0x01,             // CONNECT reply: success, IPv4 bound address
            127, 0, 0, 1, 0, 80                 // BND.ADDR + BND.PORT
        )
        val input = NoSkipInputStream(response)
        val connection = TcpConnection(
            clientIp = byteArrayOf(10, 0, 0, 2),
            clientPort = 50_000,
            serverIp = byteArrayOf(1, 1, 1, 1),
            serverPort = 443,
            tunnel = NoopTunnel,
            key = 1L,
            socksPort = 1080,
        )
        val connect = TcpConnection::class.java.getDeclaredMethod(
            "socks5Connect",
            InputStream::class.java,
            java.io.OutputStream::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }

        val accepted = connect.invoke(
            connection,
            input,
            ByteArrayOutputStream(),
            byteArrayOf(1, 1, 1, 1),
            443,
        ) as Boolean

        assertTrue(accepted)
        assertEquals(0, input.remaining, "SOCKS5 reply must be fully consumed before TLS starts")
    }

    /**
     * The network-switch path: the app is holding a socket whose upstream route is gone, and only an
     * RST tells it so. Pinned to the flag bits rather than "some packet was written" because the
     * distinction between this and [TcpConnection.close] IS the flags — a regression that silently
     * routed closeWithReset through the sendRst = false branch would still write nothing and still
     * leave the user on a dead socket for a full TCP timeout.
     */
    @Test
    fun closeWithResetTellsTheAppItsConnectionIsGone() {
        val tunnel = RecordingTunnel()
        val connection = connectionOn(tunnel)

        connection.closeWithReset()

        assertEquals(1, tunnel.packets.size, "a reset flow must emit exactly one segment")
        val flags = tcpFlagsOf(tunnel.packets.single())
        assertTrue(flags and PacketUtils.TcpFlag.RST != 0, "the segment must carry RST")
        assertTrue(flags and PacketUtils.TcpFlag.ACK != 0, "the segment must carry ACK")
        assertEquals(listOf(1L), tunnel.closedKeys, "the service must be told to drop the flow")
    }

    /** The contrast that gives the test above its meaning: ordinary teardown stays silent. */
    @Test
    fun plainCloseSaysNothingOnTheWire() {
        val tunnel = RecordingTunnel()

        connectionOn(tunnel).close()

        assertEquals(emptyList<ByteArray>(), tunnel.packets, "close() must not signal the peer")
        assertEquals(listOf(1L), tunnel.closedKeys)
    }

    /**
     * A switch landing mid-teardown must not put a second RST on a TUN the service is dropping —
     * resetFlows() closes and removes concurrently with stopEverything, so both can reach the same
     * flow. The CLOSED guard is what makes that ordering harmless.
     */
    @Test
    fun resettingAnAlreadyClosedFlowIsANoOp() {
        val tunnel = RecordingTunnel()
        val connection = connectionOn(tunnel)

        connection.close()
        connection.closeWithReset()

        assertEquals(emptyList<ByteArray>(), tunnel.packets, "a closed flow has no peer to reset")
        assertEquals(listOf(1L), tunnel.closedKeys, "the flow must be reported closed once")
    }

    private fun connectionOn(tunnel: Tunnel) = TcpConnection(
        clientIp = byteArrayOf(10, 0, 0, 2),
        clientPort = 50_000,
        serverIp = byteArrayOf(1, 1, 1, 1),
        serverPort = 443,
        tunnel = tunnel,
        key = 1L,
        socksPort = 1080,
    )

    /** Flags byte of the TCP header inside an IPv4 packet built with no IP options (IHL 5). */
    private fun tcpFlagsOf(packet: ByteArray): Int = packet[20 + 13].toInt() and 0xFF

    private class RecordingTunnel : Tunnel {
        val packets = mutableListOf<ByteArray>()
        val closedKeys = mutableListOf<Long>()

        override val relayExecutor = Executor { }
        override fun writeToTun(packet: ByteArray) { packets += packet }
        override fun protectTcp(socket: Socket) = true
        override fun protectUdp(socket: DatagramSocket) = true
        override fun onConnectionClosed(key: Long, udp: Boolean) { closedKeys += key }
        override fun reportError(msg: String) = Unit
        override fun onConnectResult(success: Boolean) = Unit
    }

    private class NoSkipInputStream(private val bytes: ByteArray) : InputStream() {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset

        override fun read(): Int = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 0xFF

        override fun skip(n: Long): Long = 0L
    }

    private object NoopTunnel : Tunnel {
        override val relayExecutor = Executor { }
        override fun writeToTun(packet: ByteArray) = Unit
        override fun protectTcp(socket: Socket) = true
        override fun protectUdp(socket: DatagramSocket) = true
        override fun onConnectionClosed(key: Long, udp: Boolean) = Unit
        override fun reportError(msg: String) = Unit
        override fun onConnectResult(success: Boolean) = Unit
    }
}
