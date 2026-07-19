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
