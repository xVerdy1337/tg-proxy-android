package com.tgwsproxy.net

import android.content.Context
import androidx.annotation.StringRes
import com.tgwsproxy.R
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

    /** Curated byedpi strategies, strongest / most-proven first. No {sni} placeholders. */
    val STRATEGIES: List<Strategy> = (listOf(
        Strategy("-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1", R.string.strategy_cascade_disorder_split),
        Strategy("-d1 -s1+s -r1+s -f-1 -t8 -a1", R.string.strategy_split_tlsrec_fake),
        Strategy("-f1+nme -t6 -a1", R.string.strategy_fake_split_ttl6),
        Strategy("-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1", R.string.strategy_cascade_split),
        // Every -A entry in this list carries -T for the reason documented on ByedpiPresetCatalog's
        // "tlsrec-double": nothing but case 'T' (byedpi/main.c) sets the timeouts, and without them
        // -A's t/r/s detectors have no trigger at all against a silent SNI drop. Keep the value in
        // sync with the catalog — a strategy that tests differently from how it later runs is worse
        // than no auto-tune — and its first field under TLS_TIMEOUT_MS above, or the strategies it
        // is meant to rescue go back to failing every sweep.
        Strategy("-T2:2:2:64 -o1 -a1 -At,r,s -d1 -a1", R.string.strategy_oob_auto),
        Strategy("-d6+s -q4+hm -o2 -a1", R.string.strategy_disorder_oob),
        Strategy("-f-1 -t8 -s1+s -a1", R.string.strategy_fake_ttl8_split),
        Strategy("-d2 -s1+s -d5+s -s10+s -d20+s -a1", R.string.strategy_cascade_step2),
        Strategy("-T2:2:2:64 -r5+s -s25+s -a1 -At,r,s -s50 -r5+s -s50+s -a1", R.string.strategy_tlsrec_split_double),
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
    data class StrategyResult(val strategy: Strategy, val hosts: List<HostResult>) {
        val allOk: Boolean get() = hosts.isNotEmpty() && hosts.all { it.ok }
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
        try {
            val args = DesyncVpnService.buildByedpiArgs(strategy.command, "127.0.0.1", port)
            loop = thread(name = "byedpi-test", isDaemon = true) {
                // Any return means this run is done with the engine — refused by the singleton
                // guard (-1), rejected for a malformed command (-2, since main() returns
                // status - 1), or finished serving. The native epilogue has already released the
                // globals or found itself superseded, so from here on we hold nothing. Keying on
                // "returned at all" rather than on -1 alone matters: a bad strategy string is the
                // COMMON case in a sweep, and -1 would have missed every one of them.
                try { proxy.startProxy(args) } catch (_: Throwable) {}
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
                return StrategyResult(
                    strategy,
                    hosts.map { HostResult(it, false, context.getString(R.string.byedpi_bad_command)) },
                )
            }
            // Test all hosts in parallel so a strategy's cost is max(host) instead of sum(host).
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
            return StrategyResult(strategy, hosts.mapIndexed { idx, host ->
                results[idx] ?: HostResult(host, false, context.getString(R.string.timeout))
            })
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
    }
}
