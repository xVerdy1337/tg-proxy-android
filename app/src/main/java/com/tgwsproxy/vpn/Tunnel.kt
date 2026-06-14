package com.tgwsproxy.vpn

import java.net.DatagramSocket
import java.net.Socket

/**
 * The bridge a [TcpConnection] / [UdpAssociation] uses to talk back to the TUN and to create
 * VPN-protected upstream sockets. Implemented by [DesyncVpnService].
 */
interface Tunnel {
    /** Write a fully-built IP packet back into the TUN (delivered to the app). Thread-safe. */
    fun writeToTun(packet: ByteArray)

    /** Exclude an upstream TCP socket from the VPN so it reaches the real network. */
    fun protectTcp(socket: Socket): Boolean

    /** Exclude an upstream UDP socket from the VPN. */
    fun protectUdp(socket: DatagramSocket): Boolean

    /** A flow finished; drop it from the NAT table. */
    fun onConnectionClosed(key: Long)
}
