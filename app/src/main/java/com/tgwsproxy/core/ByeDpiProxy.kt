package com.tgwsproxy.core

/**
 * Kotlin wrapper around the bundled native `byedpi` (ciadpi) DPI-desync engine.
 *
 * byedpi runs as a local SOCKS5 proxy on 127.0.0.1:<port>. Our [com.tgwsproxy.vpn] layer
 * captures app traffic via the TUN and relays each flow through this SOCKS5 proxy; byedpi then
 * applies the real packet-level desync (fake/split/disorder/tlsrec with TTL/seqovl fooling) that
 * a pure-Kotlin socket relay cannot do. This is the same engine "ByeDPI for Android" uses and what
 * lets us match zapret/alt12 strategies without root.
 *
 * Lifecycle: [createSocket] parses a byedpi command line and opens the listening socket, returning
 * its fd; [startProxy] runs the blocking event loop on that fd (call on a background thread);
 * [stopProxy] shuts the listening fd down so the loop exits.
 */
class ByeDpiProxy {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }

    @Volatile
    private var fd = -1

    /** Parse [args] (a full byedpi command line, argv[0] included) and open the listen socket. */
    fun createSocket(args: Array<String>): Int {
        check(fd < 0) { "proxy already running" }
        val f = jniCreateSocketWithCommandLine(args)
        if (f >= 0) fd = f
        return f
    }

    /** Run the blocking event loop. Returns when the socket is shut down. */
    fun runLoop(): Int {
        val f = fd
        check(f >= 0) { "proxy socket not created" }
        return jniStartProxy(f)
    }

    /** Shut the listen socket so [runLoop] returns. Safe to call from another thread. */
    fun stop(): Int {
        val f = fd
        if (f < 0) return 0
        val res = jniStopProxy(f)
        if (res == 0) fd = -1
        return res
    }

    private external fun jniCreateSocketWithCommandLine(args: Array<String>): Int
    private external fun jniStartProxy(fd: Int): Int
    private external fun jniStopProxy(fd: Int): Int
}
