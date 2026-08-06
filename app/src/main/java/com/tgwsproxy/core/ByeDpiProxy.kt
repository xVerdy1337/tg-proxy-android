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
        /**
         * Exit code [startProxy] reports when there is no native engine to run at all. Distinct from
         * every status byedpi itself can return — main() yields `status - 1`, and -1 is already spoken
         * for by a refused start (busy singleton / failed bind), which the VPN turns into a
         * "port busy" message. A shared spelling there would explain a missing .so as a port
         * conflict and send the user hunting for another proxy app.
         */
        const val EXIT_NATIVE_UNAVAILABLE = -3

        /**
         * Did the bundled engine actually load?
         *
         * The load lives in a guarded initializer rather than an `init {}` block because a throw out
         * of a companion initializer surfaces as [ExceptionInInitializerError] — an Error, so none of
         * the `catch (e: Exception)` guards around our two instantiation sites stops it, and merely
         * *touching* this class kills the process. That is reachable in practice on any ABI the APK
         * has no libbyedpi.so for (x86_64 emulators, Chromebooks, WSA): every screen works, then
         * enabling the bypass takes the app down with no message.
         *
         * Callers must consult this before instantiating; the accessors below degrade to a no-op
         * rather than letting an UnsatisfiedLinkError escape from a JNI call, so a missed check is a
         * clear refusal instead of a crash.
         */
        @JvmStatic
        val nativeAvailable: Boolean = try {
            System.loadLibrary("byedpi")
            true
        } catch (_: Throwable) {
            false
        }

        /**
         * The ABI this process actually runs as, for the "no engine for this CPU" message. Element 0
         * of SUPPORTED_ABIS is the device's preferred ABI, which is the one the installer picked the
         * native libs for — naming it turns an unactionable failure into a searchable one.
         */
        @JvmStatic
        fun primaryAbi(): String =
            android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"
    }

    /** Run the blocking byedpi proxy loop with [args] (argv[0] must be a dummy, e.g. "ciadpi"). */
    fun startProxy(args: Array<String>): Int =
        if (nativeAvailable) jniStartProxy(args) else EXIT_NATIVE_UNAVAILABLE

    /** Gracefully shut the listen socket so [startProxy] returns. Safe from another thread. */
    fun stopProxy(): Int = if (nativeAvailable) jniStopProxy() else EXIT_NATIVE_UNAVAILABLE

    /** Hard-close the listen socket if graceful shutdown stalls. */
    fun forceClose(): Int = if (nativeAvailable) jniForceClose() else EXIT_NATIVE_UNAVAILABLE

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    private external fun jniForceClose(): Int
}
