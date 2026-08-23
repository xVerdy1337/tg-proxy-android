package com.tgwsproxy.core

/**
 * Exit codes the JNI shim answers with, as opposed to the ones that come from byedpi's own `main()`.
 *
 * A top-level object rather than constants on [ByeDpiProxy] on purpose: that class loads
 * `libbyedpi.so` in its initializer, so anything that reads these — the VPN's failure mapping and
 * the unit test that pins them — would drag the native library into a JVM that has none. The
 * contract lives in native-lib.c; both sides have to move together.
 *
 * [MAIN_RUN_FAILED] and [MAIN_BAD_COMMAND] are byedpi's, listed here so the gap between the two
 * ranges is visible in one place. Anything this shim invents must stay outside them, which is the
 * whole point: `main()` answers -1 when `init()`/`run()` fails, and under Android that means the
 * bind lost the port. Sharing -1 with "the singleton is already claimed" made the VPN tell the user
 * to close other SOCKS apps while the real holder was an auto-tune sweep inside this same process.
 */
object ByeDpiExit {
    /** byedpi's `init()`/`run()` failed — on Android, effectively "the port is taken". */
    const val MAIN_RUN_FAILED = -1

    /** `parse_args` refused the command; `main()` answers its status - 1. */
    const val MAIN_BAD_COMMAND = -2

    /** The process-wide singleton is held by another run; nothing was touched. */
    const val ENGINE_BUSY = -11

    /** argv marshalling could not allocate; the gate was handed straight back. */
    const val ARGV_OOM = -12

    /** Did [code] come from this shim's gate rather than from a byedpi run that actually happened? */
    fun isShimRefusal(code: Int): Boolean = code == ENGINE_BUSY || code == ARGV_OOM
}

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
 *
 * Only ONE run may exist per process: the engine's listener and its busy flag are native globals.
 * [isEngineBusy] and [awaitEngineFree] report on that claim so a caller can explain a refusal
 * instead of guessing at one — neither reserves anything, [startProxy]'s own test-and-set is still
 * the only thing that claims the engine.
 */
class ByeDpiProxy {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }

        /**
         * How long a caller taking over from a run that has just been told to stop should wait for
         * the engine to actually come free. Sized for the gap between a teardown returning and the
         * loop thread's last unwinding — milliseconds in practice — not for a wedged run, which no
         * amount of waiting cures.
         */
        const val ENGINE_FREE_POLL_MS = 750L

        private const val ENGINE_FREE_STEP_MS = 25L

        /**
         * Is some run holding the engine right now? Process-wide, so a `true` here says nothing
         * about *whose* run it is — the VPN's, an auto-tune candidate's, or one that wedged.
         *
         * Already stale when it returns, by construction. Use it to explain, never to decide that
         * claiming will succeed: only [startProxy] can find that out.
         */
        fun isEngineBusy(): Boolean = flagProbe.jniIsRunning()

        /**
         * Poll until the engine reads free, up to [timeoutMs]. Returns whether it came free.
         *
         * Worth the wait at exactly one place: the moment after another run was asked to stop, where
         * its loop thread is typically microseconds from the epilogue that lowers the flag. Failing
         * without it turned an ordinary "enable the VPN right after tuning" into a hard error the
         * user could do nothing about.
         */
        fun awaitEngineFree(timeoutMs: Long = ENGINE_FREE_POLL_MS): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                if (!isEngineBusy()) return true
                if (System.nanoTime() >= deadline) return false
                try {
                    Thread.sleep(ENGINE_FREE_STEP_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return !isEngineBusy()
                }
            }
        }

        /**
         * The flag query needs an instance because the JNI entry points are non-static, and the
         * answer is about a native global rather than about any one run — so it must not be reached
         * through a caller's own [ByeDpiProxy], which would read as ownership it does not have.
         * Declared last: the initializer above must have loaded the library before this constructs.
         */
        private val flagProbe = ByeDpiProxy()
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

    /**
     * Reads the native busy flag. Reached only through [isEngineBusy]; see `flagProbe`.
     *
     * `private`, like the three above, and not `internal`: Kotlin mangles internal member names on
     * the JVM (`jniIsRunning$app_debug`), and the JNI symbol in native-lib.c is resolved from the
     * JVM name — an internal one would go unlinked until the first call threw
     * UnsatisfiedLinkError at runtime. The companion below is inside this class body, so private is
     * visibility enough.
     */
    private external fun jniIsRunning(): Boolean
}
