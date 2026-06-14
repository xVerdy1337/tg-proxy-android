package com.tgwsproxy.core

/**
 * Kotlin wrapper around the bundled native `byedpi` (ciadpi) DPI-desync engine — v0.17.3,
 * the exact version ByeByeDPI (BBD) ships, so community command strings work verbatim.
 *
 * byedpi runs as a local SOCKS5 proxy on 127.0.0.1:<port>. Our [com.tgwsproxy.vpn] layer
 * captures app traffic via the TUN and relays each flow through this SOCKS5 proxy; byedpi then
 * applies the real packet-level desync (fake/split/disorder/tlsrec with TTL/seqovl fooling) that
 * a pure-Kotlin socket relay cannot do. This is the same engine "ByeDPI for Android" uses and what
 * lets us match zapret/alt12 strategies without root.
 *
 * Lifecycle: [startProxy] takes a full byedpi argv (argv[0] = "ciadpi") and runs the blocking
 * proxy event loop — call it on a background thread; it returns only when the proxy stops.
 * [stopProxy] shuts the listening socket down so the loop exits cleanly; [forceClose] hard-closes
 * it if the graceful shutdown doesn't return in time.
 */
class ByeDpiProxy {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }

    /** Run the blocking byedpi proxy loop with [args] (argv[0] must be a dummy, e.g. "ciadpi"). */
    fun startProxy(args: Array<String>): Int = jniStartProxy(args)

    /** Gracefully shut the listen socket so [startProxy] returns. Safe from another thread. */
    fun stopProxy(): Int = jniStopProxy()

    /** Hard-close the listen socket if graceful shutdown stalls. */
    fun forceClose(): Int = jniForceClose()

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    private external fun jniForceClose(): Int
}
