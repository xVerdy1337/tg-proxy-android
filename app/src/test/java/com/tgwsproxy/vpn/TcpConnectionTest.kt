package com.tgwsproxy.vpn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TcpConnectionTest {

    @Test
    fun socksConnectConsumesTheEntireSuccessReply() {
        val response = byteArrayOf(
            0x05, 0x00,                         // greeting: SOCKS5, no authentication
            0x05, 0x00, 0x00, 0x01,             // CONNECT reply: success, IPv4 bound address
            127, 0, 0, 1, 0, 80                 // BND.ADDR + BND.PORT
        )
        // DataInputStream.readFully never calls skip(); available() tracks the unread remainder.
        val input = ByteArrayInputStream(response)
        val connection = newConnection(NoopTunnel)

        val accepted = connection.socks5Connect(
            input,
            ByteArrayOutputStream(),
            byteArrayOf(1, 1, 1, 1),
            443,
        )

        assertTrue(accepted)
        assertEquals(0, input.available(), "SOCKS5 reply must be fully consumed before TLS starts")
    }

    @Test
    fun outOfOrderEmptyFinIsNotAccepted() {
        val tunnel = RecordingTunnel()
        val connection = newConnection(tunnel)
        connection.onSyn(clientSeq = 1000, window = 65535)
        assertEquals(1, tunnel.written.size) // SYN-ACK

        // An empty-payload FIN with a wrong seq must NOT be ACKed or advance rcvNxt.
        connection.onPacket(seq = 5000, ack = 0, flags = PacketUtils.TcpFlag.FIN, window = 65535, payload = ByteArray(0))
        assertEquals(1, tunnel.written.size)

        // The in-sequence FIN (rcvNxt == clientSeq + 1 == 1001) IS ACKed.
        connection.onPacket(seq = 1001, ack = 0, flags = PacketUtils.TcpFlag.FIN, window = 65535, payload = ByteArray(0))
        assertEquals(2, tunnel.written.size)
        assertEquals(
            PacketUtils.TcpFlag.ACK,
            PacketUtils.tcpFlags(tunnel.written[1]) and PacketUtils.TcpFlag.ACK,
        )
    }

    @Test
    fun synRetransmitRepliesWithSynAckCarryingMss() {
        val tunnel = RecordingTunnel()
        val connection = newConnection(tunnel)
        connection.onSyn(clientSeq = 1000, window = 65535)

        // Client retransmits its SYN → we resend the SYN-ACK, with the MSS option like the original.
        connection.onPacket(seq = 1000, ack = 0, flags = PacketUtils.TcpFlag.SYN, window = 65535, payload = ByteArray(0))

        assertEquals(2, tunnel.written.size)
        val synAck = tunnel.written[1]
        assertTrue(PacketUtils.isWellFormedIpv4L4(synAck))
        assertEquals(
            PacketUtils.TcpFlag.SYN or PacketUtils.TcpFlag.ACK,
            PacketUtils.tcpFlags(synAck) and (PacketUtils.TcpFlag.SYN or PacketUtils.TcpFlag.ACK),
        )
        // 24-byte TCP header = 20 bytes + 4-byte MSS option
        assertEquals(24, PacketUtils.tcpDataOffset(synAck))
    }

    private fun newConnection(tunnel: Tunnel) = TcpConnection(
        clientIp = byteArrayOf(10, 0, 0, 2),
        clientPort = 50_000,
        serverIp = byteArrayOf(1, 1, 1, 1),
        serverPort = 443,
        tunnel = tunnel,
        key = 1L,
        socksPort = 1080,
    )

    private class RecordingTunnel : Tunnel {
        val written = ArrayList<ByteArray>()
        override val relayExecutor = Executor { } // drop runnables: no real upstream in unit tests
        override fun writeToTun(packet: ByteArray) {
            written.add(packet)
        }
        override fun protectTcp(socket: Socket) = true
        override fun protectUdp(socket: DatagramSocket) = true
        override fun onConnectionClosed(key: Long, udp: Boolean) = Unit
        override fun reportError(msg: String) = Unit
        override fun onConnectResult(success: Boolean) = Unit
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
