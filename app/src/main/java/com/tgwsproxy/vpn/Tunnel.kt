package com.tgwsproxy.vpn

import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.Executor

/**
 * The bridge a [TcpConnection] / [UdpAssociation] uses to talk back to the TUN and to create
 * VPN-protected upstream sockets. Implemented by [DesyncVpnService].
 */
interface Tunnel {
    /**
     * Shared pool for per-flow blocking relay loops. A cached pool (not raw `thread{}` per flow,
     * nor a hard-capped dispatcher) reuses idle threads to cut allocation churn while still growing
     * for many concurrent long-lived flows — a fixed cap would starve a VPN holding lots of idle
     * sockets, since each flow's downstream pump occupies a thread for the flow's whole lifetime.
     */
    val relayExecutor: Executor

    /** Write a fully-built IP packet back into the TUN (delivered to the app). Thread-safe. */
    fun writeToTun(packet: ByteArray)

    /** Exclude an upstream TCP socket from the VPN so it reaches the real network. */
    fun protectTcp(socket: Socket): Boolean

    /** Exclude an upstream UDP socket from the VPN. */
    fun protectUdp(socket: DatagramSocket): Boolean

    /**
     * A flow finished; drop it from the NAT table. [udp] selects which table to clean so a TCP
     * flow can never evict a UDP association that happens to share the same (port,ip,port) tuple.
     */
    fun onConnectionClosed(key: Long, udp: Boolean)

    /**
     * Surface a human-readable diagnostic (e.g. "protect failed" / "connect: timeout") so the UI
     * can show *why* connections are dying. Used to debug the userspace data path on-device.
     */
    fun reportError(msg: String)

    /**
     * Tally each upstream TCP connect attempt's outcome so the UI can show how many flows actually
     * reach the real server vs. fail — the decisive signal for "does the data path work for this
     * app" without needing logcat on-device.
     */
    fun onConnectResult(success: Boolean)
}
