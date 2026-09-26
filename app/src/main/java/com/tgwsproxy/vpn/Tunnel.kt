package com.tgwsproxy.vpn

import java.net.DatagramSocket
import java.util.concurrent.Executor

/**
 * The bridge a [TcpConnection] / [UdpAssociation] uses to talk back to the TUN and to create
 * VPN-protected upstream sockets. Implemented by [DesyncVpnService].
 */
interface Tunnel {
    /**
     * Shared, bounded pool for per-flow blocking relay loops; each flow's pump occupies a thread
     * for the flow's whole lifetime. Rejects work once full or stopped (callers drop the flow).
     */
    val relayExecutor: Executor

    /** Write a fully-built IP packet back into the TUN (delivered to the app). Thread-safe. */
    fun writeToTun(packet: ByteArray)

    /** Exclude an upstream UDP socket from the VPN. */
    fun protectUdp(socket: DatagramSocket): Boolean

    /** A TCP flow finished; drop it from the TCP table. */
    fun onConnectionClosed(key: Long)

    /**
     * A UDP association closed. The table drops the entry only while it still maps to
     * [association]: a DNS flow closes itself from its reader thread, and a query reusing the same
     * source port may already have installed a fresh association under that key, which a key-only
     * removal would orphan.
     */
    fun onUdpAssociationClosed(key: Long, association: UdpAssociation)

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
