package com.tgwsproxy.net

import android.content.Context
import androidx.annotation.StringRes
import com.tgwsproxy.R
import com.tgwsproxy.core.ByeDpiExit
import com.tgwsproxy.core.ByeDpiProxy
import com.tgwsproxy.vpn.ByedpiPresetCatalog
import com.tgwsproxy.vpn.DesyncVpnService
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Auto-picks a working DPI-bypass strategy the way ByeByeDPI's proxytest does, but end-to-end:
 * for each candidate byedpi command it spins up a *real* local byedpi SOCKS5 instance and then
 * completes a genuine TLS handshake to each target host (real SNI) through it. If the handshake
 * succeeds, the provider DPI didn't reset the flow → that strategy actually unblocks the host.
 *
 * This is the honest signal the pure-Kotlin [HelloProbe] can't give: it runs the same native
 * engine that carries real traffic, so FAKE/TTL strategies are covered too.
 *
 * IMPORTANT: byedpi allows only ONE instance at a time (native global guard), so the VPN must be
 * OFF while tuning, and strategies are tested strictly sequentially.
 */
object StrategyTester {

    private const val SOCKS_CONNECT_TIMEOUT_MS = 3500

    /**
     * How long one blocking read of the handshake may take — and half of a coupled pair, which is
     * why it is not private. The other half is the first field of the -T on every command that
     * ships an -A group (the entries below and ByedpiPresetCatalog's tlsrec-double / auto-oob):
     * byedpi arms it as TCP_USER_TIMEOUT on the *upstream* socket, so when the DPI swallows the
     * ClientHello nothing at all happens on our socket until that deadline fires, on_torst moves to
     * the next -A group and the saved ClientHello is replayed. Detect + reconnect + a whole second
     * handshake therefore has to fit in one window here. At 3.5s against an 8s -T it could not, and
     * the auto strategies were unselectable no matter how well they worked — the failure is silent
     * from both ends, so ByedpiArgsTest pins the relationship instead of a comment alone.
     */
    const val TLS_TIMEOUT_MS = 5000

    // How long the local byedpi listener gets to start accepting before we call the strategy dead.
    private const val SOCKS_READY_MS = 3_000L

    data class Strategy(val command: String, @StringRes val labelRes: Int)

    /**
     * The sweep entry for a catalog preset, looked up by id instead of re-spelled here. This list
     * used to hard-code its own copy of several catalog commands and one had already drifted a
     * token apart ("auto-oob" grew a trailing -a1) — a strategy that tests differently from how
     * the VPN later runs it is worse than no auto-tune, so the catalog is the single source.
     */
    private fun fromCatalog(id: String): Strategy {
        val preset = ByedpiPresetCatalog.byId(id) ?: error("ByedpiPresetCatalog is missing preset '$id'")
        return Strategy(preset.command, preset.labelRes)
    }

    /** Curated byedpi strategies, strongest / most-proven first. No {sni} placeholders. */
    val STRATEGIES: List<Strategy> = (listOf(
        fromCatalog(DesyncVpnService.PRESET_AUTO),
        fromCatalog(DesyncVpnService.PRESET_TLSREC),
        Strategy("-f1+nme -t6 -a1", R.string.strategy_fake_split_ttl6),
        fromCatalog(DesyncVpnService.PRESET_SPLIT),
        // The -A entries carry -T for the reason documented on ByedpiPresetCatalog's
        // "tlsrec-double", with the first field bounded by TLS_TIMEOUT_MS above; ByedpiArgsTest
        // pins both, and fails if these entries ever drift from the catalog again.
        fromCatalog("auto-oob"),
        fromCatalog("disorder-oob"),
        Strategy("-f-1 -t8 -s1+s -a1", R.string.strategy_fake_ttl8_split),
        Strategy("-d2 -s1+s -d5+s -s10+s -d20+s -a1", R.string.strategy_cascade_step2),
        fromCatalog("tlsrec-double"),
        Strategy("-d1 -s4 -d8 -s1+s -d5+s -s10+s -d20+s -a1", R.string.strategy_mix_disorder_split),
    ) + ByedpiPresetCatalog.autotunePresets.map { preset ->
        Strategy(preset.command, preset.labelRes)
    }).distinctBy { it.command }

    /** Friendly label resource for a saved command if it matches a known strategy, else null. */
    fun labelResForCommand(command: String): Int? =
        STRATEGIES.firstOrNull { it.command == command.trim() }?.labelRes

    fun labelForCommand(context: Context, command: String): String? =
        labelResForCommand(command)?.let { context.getString(it) }

    data class HostResult(val host: String, val ok: Boolean, val detail: String)
    data class StrategyResult(
        val strategy: Strategy,
        val hosts: List<HostResult>,
        /**
         * Did the native run actually end, or did teardown give up on it?
         *
         * Not a detail: the engine is a process-wide singleton whose busy flag is lowered only by
         * jniStartProxy's epilogue — jniStopProxy and jniForceClose deliberately leave it raised.
         * So a loop thread that outlives both joins below holds the engine for the life of the
         * process, and every later VPN start is refused. The sweep needs to know that rather than
         * assume it, which is why this is reported instead of inferred from the coroutine finishing.
         */
        val engineReleased: Boolean = true,
        /**
         * The run never happened: the singleton was already claimed, so startProxy() came straight
         * back with [ByeDpiExit.ENGINE_BUSY] without touching anything.
         *
         * Reported apart from the host results because it says nothing whatsoever about the strategy
         * — and the sweep used to read it as everything. A refusal makes the listener never answer,
         * which is [DesyncVpnService.awaitSocksReady]'s failure path, which scored the candidate as a
         * bad command; so once anything else took the engine mid-sweep (the VPN coming up from the
         * quick-settings tile is the documented way in) every remaining candidate was recorded as
         * broken and the best of that garbage was persisted and cached. A refusal must stop the sweep.
         */
        val engineRefused: Boolean = false,
    ) {
        val allOk: Boolean get() = hosts.isNotEmpty() && hosts.all { it.ok }
    }

    /**
     * Whether the last [testStrategy] left the engine held, kept past the call so a cancelled sweep
     * can still find out. The sweep's own result is discarded on that path — the coroutine unwinds
     * out of its withContext instead of returning — and this is the one fact that must survive it,
     * because the alternative is telling the user the tuning stopped cleanly while the engine is
     * wedged and every later VPN start is going to be refused.
     *
     * Written by whichever thread ran the sweep, read from the main thread in the cancel handler.
     */
    @Volatile
    private var lastRunHeldEngine: Boolean = false

    /**
     * Is the engine still held by the run that last went through [testStrategy]?
     *
     * Both halves are needed. The flag alone goes stale: a loop thread that outlived teardown may
     * unwind a moment later, and its epilogue then frees the engine while the flag still reads held.
     * The native busy bit alone is not attributable: it is process-wide, so a VPN that came up in the
     * meantime raises it too. Together they only say yes when our own run gave up AND something is
     * still holding on, which is the case worth reporting.
     */
    fun engineStillHeldByLastRun(): Boolean = lastRunHeldEngine && ByeDpiProxy.isEngineBusy()

    /**
     * Drop the verdict above, so a new sweep cannot be judged by an older one's teardown.
     *
     * Needed because the verdict outlives the call that produced it — that is the whole point of it —
     * and a sweep cancelled before its first candidate has finished would otherwise answer with
     * whatever the previous sweep left behind. The busy-flag half of the test does not save us there:
     * it is process-wide, so a VPN the user enabled in between raises it too, and a stale `true`
     * would blame the engine for holding on to a run that ended minutes ago.
     */
    fun forgetLastRun() {
        lastRunHeldEngine = false
    }

    /** Complete a real TLS handshake to [host] through the local SOCKS5 proxy on [socksPort]. */
    private fun testHostThroughSocks(context: Context, host: String, port: Int, socksPort: Int): HostResult {
        var raw: Socket? = null
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            raw = Socket(proxy)
            // Unresolved address → Java sends the hostname to byedpi, which resolves + desyncs it.
            raw.connect(InetSocketAddress.createUnresolved(host, port), SOCKS_CONNECT_TIMEOUT_MS)
            raw.soTimeout = TLS_TIMEOUT_MS
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true) as SSLSocket
            ssl.soTimeout = TLS_TIMEOUT_MS
            ssl.startHandshake() // real ClientHello w/ real SNI; throws if the DPI resets the flow
            try { ssl.close() } catch (_: Exception) {}
            return HostResult(host, true, context.getString(R.string.tls_ok))
        } catch (e: Exception) {
            return HostResult(host, false, e.message ?: e.javaClass.simpleName)
        } finally {
            try { raw?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Run [strategy] through a fresh local byedpi instance on [port] and test every host in [hosts].
     * Caller MUST ensure the VPN is off. Returns per-host TLS results.
     */
    fun testStrategy(context: Context, strategy: Strategy, hosts: List<String>, port: Int): StrategyResult {
        val proxy = ByeDpiProxy()
        var loop: Thread? = null
        // Written by the loop thread, read by the finally below, so it has to publish safely.
        val returned = AtomicBoolean(false)
        // Same publication requirement, and the reason it is separate from `returned`: a refusal
        // returns too, so "the thread came back" cannot tell the two apart on its own.
        val refused = AtomicBoolean(false)
        // Built inside the try, returned AFTER the finally: whether the engine is free is only
        // knowable once teardown has run, so the result cannot be handed back from inside the try.
        val result: StrategyResult = try {
            val args = DesyncVpnService.buildByedpiArgs(strategy.command, "127.0.0.1", port)
            loop = thread(name = "byedpi-test", isDaemon = true) {
                // Any return means this run is done with the engine — refused by the singleton
                // guard, rejected for a malformed command (-2, since main() returns status - 1), or
                // finished serving. The native epilogue has already released the globals or found
                // itself superseded, so from here on we hold nothing. Keying on "returned at all"
                // rather than on one code matters: a bad strategy string is the COMMON case in a
                // sweep, and any single code would have missed most of them.
                //
                // The refusal is still worth separating out, because it is the one return that says
                // nothing about the strategy: the engine was already someone else's, so this
                // candidate was never tested at all.
                try {
                    val code = proxy.startProxy(args)
                    if (code == ByeDpiExit.ENGINE_BUSY) refused.set(true)
                } catch (_: Throwable) {}
                returned.set(true)
            }
            // Readiness means the SOCKS listener accepts a connection, not merely that its thread
            // outlived a fixed sleep: on a slow or loaded device every host probe below raced a
            // not-yet-bound port, so a perfectly good strategy was recorded as a failure. Poll a
            // real loopback connect instead — the same signal DesyncVpnService waits for before it
            // relays anything. The liveness predicate keeps an invalid command, which makes
            // startProxy() return at once, from stalling the sweep for the whole deadline.
            val ready = DesyncVpnService.awaitSocksReady(port, SOCKS_READY_MS) {
                loop?.isAlive == true
            }
            if (!ready) {
                StrategyResult(
                    strategy,
                    hosts.map { HostResult(it, false, context.getString(R.string.byedpi_bad_command)) },
                )
            } else {
                // Test all hosts in parallel so a strategy's cost is max(host), not sum(host).
                val results = arrayOfNulls<HostResult>(hosts.size)
                val workers = hosts.mapIndexed { idx, host ->
                    thread(name = "probe-$host", isDaemon = true) {
                        results[idx] = testHostThroughSocks(context, host, 443, port)
                    }
                }
                // Derived from the two socket deadlines on purpose: a worker that is legitimately
                // sitting through a -T detect and the replayed handshake that follows must not be
                // abandoned one join short of the read it is about to complete — that scores the
                // strategy dead for the same reason a too-short soTimeout does.
                val budget = (SOCKS_CONNECT_TIMEOUT_MS + TLS_TIMEOUT_MS + 1000).toLong()
                workers.forEach { try { it.join(budget) } catch (_: InterruptedException) {} }
                StrategyResult(strategy, hosts.mapIndexed { idx, host ->
                    results[idx] ?: HostResult(host, false, context.getString(R.string.timeout))
                })
            }
        } finally {
            // Teardown does not act on `proxy` — stopProxy/forceClose reach the process-wide native
            // globals, i.e. whichever run holds them right now. A refused start never held them, so
            // firing here would shut down the listener that DOES: enable the VPN and tap Auto tune
            // during its start-up and this run kills the VPN's SOCKS port while the TUN stays up and
            // the UI still reads "running", turning every captured flow into connection-refused with
            // nothing surfaced. Only a run we know has returned skips — if the thread has not
            // reported yet it may still be on its way into start_event_loop(), and then the engine
            // is ours. On the normal path startProxy() is still blocked in main() at this point,
            // so the teardown below is what makes it return.
            //
            // The reverse race is the micro-window between startProxy() returning and
            // returned.set(true): this check can still read false while the engine has already
            // released its globals, so the stop/force-close below fire at a released engine.
            // That is survivable only because jniStopProxy and jniForceClose are REQUIRED to be
            // idempotent no-ops once the globals are free — otherwise tearing down a finished
            // run could kill the next run that legitimately claimed the engine.
            if (!returned.get()) {
                try { proxy.stopProxy() } catch (_: Throwable) {}
                try { loop?.join(1500) } catch (_: Throwable) {}
                if (loop?.isAlive == true) {
                    try { proxy.forceClose() } catch (_: Throwable) {}
                    // Join again after the force-close: the engine is a process-wide singleton, so
                    // returning while this run is still unwinding lets the NEXT strategy start on
                    // top of it — the dying run then tears down its successor's state as it exits.
                    try { loop?.join(1500) } catch (_: Throwable) {}
                }
            }
        }
        // Read only now, after teardown: `returned` still false here means the loop thread outlived
        // both joins AND the force-close, i.e. it is still inside main() and the native busy flag is
        // still raised — nothing else lowers it. The sweep has to see that, because the next start
        // of anything (the VPN included) will be refused for the life of the process.
        //
        // Published to the object as well as returned, so a sweep that is cancelled — and therefore
        // throws this result away — still leaves the fact behind for its completion handler.
        val held = !returned.get()
        lastRunHeldEngine = held
        return result.copy(engineReleased = !held, engineRefused = refused.get())
    }
}
