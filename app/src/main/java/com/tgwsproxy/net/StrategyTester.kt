package com.tgwsproxy.net

import com.tgwsproxy.core.ByeDpiProxy
import com.tgwsproxy.vpn.DesyncVpnService
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
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
    private const val TLS_TIMEOUT_MS = 3500
    private const val BIND_WAIT_MS = 350L

    data class Strategy(val command: String, val label: String)

    /** Curated byedpi strategies, strongest / most-proven first. No {sni} placeholders. */
    val STRATEGIES: List<Strategy> = listOf(
        Strategy("-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1", "Каскад disorder+split"),
        Strategy("-d1 -s1+s -r1+s -f-1 -t8 -a1", "Split+tlsrec+FAKE (TTL 8)"),
        Strategy("-f1+nme -t6 -a1", "FAKE split (TTL 6)"),
        Strategy("-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1", "Каскад split"),
        Strategy("-o1 -a1 -At,r,s -d1 -a1", "OOB + авто"),
        Strategy("-d6+s -q4+hm -o2 -a1", "Disorder + OOB"),
        Strategy("-f-1 -t8 -s1+s -a1", "FAKE (TTL 8) + split"),
        Strategy("-d2 -s1+s -d5+s -s10+s -d20+s -a1", "Каскад (шаг 2)"),
        Strategy("-r5+s -s25+s -a1 -At,r,s -s50 -r5+s -s50+s -a1", "tlsrec + split (двойной)"),
        Strategy("-d1 -s4 -d8 -s1+s -d5+s -s10+s -d20+s -a1", "Микс disorder/split"),
    )

    /** Friendly label for a saved command if it matches a known strategy, else null. */
    fun labelForCommand(command: String): String? =
        STRATEGIES.firstOrNull { it.command == command.trim() }?.label

    data class HostResult(val host: String, val ok: Boolean, val detail: String)
    data class StrategyResult(val strategy: Strategy, val hosts: List<HostResult>) {
        val allOk: Boolean get() = hosts.isNotEmpty() && hosts.all { it.ok }
    }

    /** Complete a real TLS handshake to [host] through the local SOCKS5 proxy on [socksPort]. */
    private fun testHostThroughSocks(host: String, port: Int, socksPort: Int): HostResult {
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
            return HostResult(host, true, "TLS ок")
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
    fun testStrategy(strategy: Strategy, hosts: List<String>, port: Int): StrategyResult {
        val proxy = ByeDpiProxy()
        var loop: Thread? = null
        try {
            val args = DesyncVpnService.buildByedpiArgs(strategy.command, "127.0.0.1", port)
            loop = thread(name = "byedpi-test", isDaemon = true) {
                try { proxy.startProxy(args) } catch (_: Throwable) {}
            }
            Thread.sleep(BIND_WAIT_MS)
            if (loop?.isAlive != true) {
                return StrategyResult(strategy, hosts.map { HostResult(it, false, "byedpi не стартовал (плохая команда)") })
            }
            // Test all hosts in parallel so a strategy's cost is max(host) instead of sum(host).
            val results = arrayOfNulls<HostResult>(hosts.size)
            val workers = hosts.mapIndexed { idx, host ->
                thread(name = "probe-$host", isDaemon = true) {
                    results[idx] = testHostThroughSocks(host, 443, port)
                }
            }
            val budget = (SOCKS_CONNECT_TIMEOUT_MS + TLS_TIMEOUT_MS + 1000).toLong()
            workers.forEach { try { it.join(budget) } catch (_: InterruptedException) {} }
            return StrategyResult(strategy, hosts.mapIndexed { idx, host ->
                results[idx] ?: HostResult(host, false, "таймаут")
            })
        } finally {
            try { proxy.stopProxy() } catch (_: Throwable) {}
            try { loop?.join(1500) } catch (_: Throwable) {}
            if (loop?.isAlive == true) try { proxy.forceClose() } catch (_: Throwable) {}
        }
    }
}
