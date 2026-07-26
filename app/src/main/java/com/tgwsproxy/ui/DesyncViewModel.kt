package com.tgwsproxy.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tgwsproxy.R
import com.tgwsproxy.net.HelloProbe
import com.tgwsproxy.net.StrategyTester
import com.tgwsproxy.vpn.ByedpiPresetCatalog
import com.tgwsproxy.vpn.DesyncVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class DesyncSettings(
    val preset: String = DesyncVpnService.PRESET_AUTO,
    val blockQuic: Boolean = true,
    val allApps: Boolean = true,
    /** Custom byedpi command line; empty = use the preset's built-in strategy. */
    val byedpiCmd: String = "",
)

/** One service's probe results across the methods we test directly. */
data class ServiceProbe(
    val host: String,
    val plain: HelloProbe.Outcome? = null,
    val tlsrec: HelloProbe.Outcome? = null,
    val split: HelloProbe.Outcome? = null,
) {
    val anyPass: Boolean get() = tlsrec == HelloProbe.Outcome.PASS || split == HelloProbe.Outcome.PASS
    val bestPreset: String? get() = when {
        tlsrec == HelloProbe.Outcome.PASS -> DesyncVpnService.PRESET_TLSREC
        split == HelloProbe.Outcome.PASS -> DesyncVpnService.PRESET_SPLIT
        else -> null
    }
}

data class ProbeUiState(
    val checking: Boolean = false,
    val results: List<ServiceProbe> = emptyList(),
    val finishedAt: Long = 0L,
)

/** Progress + outcome of the automatic strategy tuner ("Подобрать автоматически"). */
data class AutoTuneUiState(
    val running: Boolean = false,
    /**
     * The user asked to stop and the sweep is unwinding. [running] deliberately stays true meanwhile:
     * the candidate under test holds the process-wide byedpi engine until its own teardown returns,
     * and a second sweep started before then would be refused by the native single-instance guard and
     * score every strategy as dead. Nothing further is launched once this is set, so it is the cue to
     * say the stop is in flight (R.string.auto_tune_cancelling) and to stop offering a second cancel;
     * the card flips to a finished one once that teardown has returned — which is not the same as the
     * engine being free, see the completion handler in [runAutoTune].
     */
    val cancelling: Boolean = false,
    /**
     * Candidates already **finished**, not the one being tested — the bar renders index/total, so it
     * has to read empty while the first candidate runs and full only when the sweep is really over.
     */
    val index: Int = 0,
    val total: Int = 0,
    val currentLabel: String = "",
    val finished: Boolean = false,
    val foundLabel: String? = null,
    val foundCommand: String? = null,
    val error: String? = null,
    /** Per-host TLS-handshake result from the last/winning strategy (host → reachable). */
    val hostOk: Map<String, Boolean> = emptyMap(),
    /**
     * Targets left out of the sweep because the resolver came back with a definitive "no such
     * host". Reported as skipped, never as blocked, and deliberately absent from [hostOk] so their
     * row reads "not checked". A lookup that merely ran out of budget is NOT listed here — it is
     * swept like any other host, because a check that never finished is not a fact about the
     * network. Stays empty when every target was unresolved: that case never sweeps, see [error].
     */
    val unresolvedHosts: List<String> = emptyList(),
)

/** An installed app the user can choose to keep off the bypass. */
data class AppInfo(
    val pkg: String,
    val label: String,
    /** Built-in exclusions (banks/gov/retail) are always off the bypass and can't be unchecked. */
    val builtIn: Boolean,
)

/**
 * UI state holder for the "Разблокировка" tab. Live runtime state (running / flows / bytes) comes
 * straight from [DesyncVpnService.state]; user settings are persisted to prefs and re-read so they
 * survive restarts. Starting the VPN itself is driven from the Activity (needs system consent).
 */
class DesyncViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        // Versioned deliberately. A cache entry is a raw byedpi command string, and entries written
        // before the -A strategies gained their -T timeouts hold the older, inert spelling. Such an
        // entry is tested first and — since every group other than -A behaves exactly as it did —
        // usually wins again, pinning the user to a command whose auto sections can never fire.
        // A new prefix retires those winners instead of letting them re-elect themselves.
        const val AUTO_TUNE_CACHE_PREFIX = "auto_tune_cache_v2_"

        /**
         * Whole budget for the pre-sweep name lookups. Generous against a working resolver (tens of
         * milliseconds) and far below the resolver's own retry ladder, which is the point: we would
         * rather abandon a slow lookup than spend ten seconds in front of a sweep. Expiring is
         * cheap by design — an unanswered lookup leaves its host in the sweep, it never skips it.
         */
        const val DNS_TIMEOUT_MS = 3_000L

        /**
         * Loopback ports the tuner rotates through. Deliberately disjoint from the VPN's own SOCKS
         * candidates so a probe run can never be handed the port a live VPN session is using.
         */
        val AUTO_TUNE_PORTS = intArrayOf(1081, 1082, 1083, 1084)
    }

    val vpnState: StateFlow<DesyncVpnService.VpnState> = DesyncVpnService.state

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DesyncSettings> = _settings.asStateFlow()

    private val _probe = MutableStateFlow(ProbeUiState())
    val probe: StateFlow<ProbeUiState> = _probe.asStateFlow()

    private val _autoTune = MutableStateFlow(AutoTuneUiState())
    val autoTune: StateFlow<AutoTuneUiState> = _autoTune.asStateFlow()

    /** The sweep in flight, so [cancelAutoTune] has something to stop. Main thread only. */
    private var autoTuneJob: Job? = null

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _excluded = MutableStateFlow(loadExcluded())
    val excluded: StateFlow<Set<String>> = _excluded.asStateFlow()

    val builtInExcluded: Set<String> = DesyncVpnService.EXCLUDED_APPS.toSet()

    init {
        migrateSavedCommand()
    }

    /**
     * Hosts we probe and tune against — raw hostnames only. The ViewModel deliberately carries no
     * display names: its only Context is the Application one, whose locale can differ from the
     * composition's under a per-app language override, so any label built here would print
     * differently than the same service's label on screen. The UI resolves names from these hosts.
     */
    private fun targets(): List<String> = listOf(
        "www.youtube.com",
        "redirector.googlevideo.com",
        "www.instagram.com",
    )

    /**
     * Directly test (no VPN needed) whether each method bypasses the provider DPI for the target
     * services. Best run with the VPN OFF so it measures the raw network + method efficacy.
     */
    fun runProbe() {
        if (_probe.value.checking) return
        val app = getApplication<Application>()
        _probe.value = ProbeUiState(checking = true)
        viewModelScope.launch {
            val targets = targets()
            val results = withContext(Dispatchers.IO) {
                targets.map { host ->
                    ServiceProbe(
                        host = host,
                        plain = HelloProbe.probe(app, host, method = HelloProbe.Method.PLAIN).outcome,
                        tlsrec = HelloProbe.probe(app, host, method = HelloProbe.Method.TLSREC).outcome,
                        split = HelloProbe.probe(app, host, method = HelloProbe.Method.SPLIT).outcome,
                    )
                }
            }
            _probe.value = ProbeUiState(
                checking = false,
                results = results,
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * The "grandma button": run every candidate strategy through the real native byedpi engine and
     * keep the one that completes a TLS handshake to the most targets (at least one), stopping early
     * when a strategy takes them all. Targets the resolver definitively refuses are dropped before
     * the sweep and never scored. On success the winner is saved as the active byedpi command. Requires
     * the VPN to be OFF (engine allows one instance at a time).
     */
    fun runAutoTune() {
        // Also what holds a re-run off during a cancel's wind-down: on that path the body throws out
        // of its withContext without publishing anything, so `running` is lowered only by the
        // completion handler at the bottom of this function, which cannot run before the job reaches
        // its final state — i.e. not before the candidate in flight has been through StrategyTester's
        // teardown.
        if (_autoTune.value.running) return
        val app = getApplication<Application>()
        val vpn = vpnState.value
        // All three flags block: the VPN claims the process-wide byedpi engine at the top of startup
        // (isStarting), flips isRunning seconds later, and still owns it all through teardown
        // (isStopping) until stopByedpi's 2s+1.5s joins return. Stop-during-startup is why isStopping
        // is load-bearing rather than redundant — it clears isStarting without isRunning ever having
        // been set, so for those seconds the other two read false over a live engine. A sweep started
        // there gets its first candidate (the cached/saved command — the one most likely to be right)
        // refused by the native single-instance check, scores it as a failure, and caches a worse one.
        if (vpn.isRunning || vpn.isStarting || vpn.isStopping) {
            _autoTune.value = AutoTuneUiState(
                finished = true,
                error = app.getString(R.string.auto_tune_vpn_running),
            )
            return
        }
        val hosts = targets()
        val networkCacheKey = autoTuneNetworkCacheKey()
        val cached = networkCacheKey
            ?.let { prefs().getString(AUTO_TUNE_CACHE_PREFIX + it, null) }
            ?.let { ByedpiPresetCatalog.migrateCommand(it) }
        // Test the currently-saved command first (instant if it still works), then the curated list.
        // Current by construction: repaired once at init for prefs written by an older build, and
        // by setByedpiCmd for everything written since. It matters here because a superseded -A
        // spelling still passes on its non-auto groups, so as candidate #0 it would win the sweep
        // and be written straight back, pinning the user to a command whose auto sections can
        // never fire.
        val saved = _settings.value.byedpiCmd.trim()
        val strategies = buildList {
            if (!cached.isNullOrEmpty()) {
                add(
                    StrategyTester.Strategy(
                        cached,
                        StrategyTester.labelResForCommand(cached) ?: R.string.custom_command,
                    )
                )
            }
            if (saved.isNotEmpty() && saved != cached) {
                add(
                    StrategyTester.Strategy(
                        saved,
                        StrategyTester.labelResForCommand(saved) ?: R.string.current_command,
                    )
                )
            }
            addAll(StrategyTester.STRATEGIES.filter { it.command != saved && it.command != cached })
        }
        _autoTune.value = AutoTuneUiState(running = true, total = strategies.size)
        val job = viewModelScope.launch {
            // Resolve once before sweeping: a host the resolver refuses outright is out of scope,
            // not blocked. This asks the same resolver the bypass itself will use — the sweep runs with
            // the VPN off, and once it is up our package is off the tunnel anyway (self-excluded in
            // all-apps mode, absent from the allow-list otherwise), so the 8.8.8.8/1.1.1.1 the VPN
            // puts on the TUN never applies to us or to byedpi. Where the underlying resolver
            // NXDOMAINs one target, every strategy scored a host short, allOk could never be true so
            // the early exit below never fired and the sweep ground through all ~28 candidates, and
            // the user was told no method unblocked a service no desync could ever have reached.
            // Never cached, per network or otherwise: re-asking costs milliseconds here.
            val dns = withContext(Dispatchers.IO) {
                // A broken check must not become a DNS accusation — fall back to sweeping everything.
                try { dnsOutcomes(hosts) } catch (_: Throwable) { emptyMap<String, DnsOutcome>() }
            }
            // Only a definitive "no such host" may remove a target. A lookup that ran out of budget
            // is swept exactly as it was before this check existed: worst case it fails in the sweep
            // the way it always did, which beats being dropped from scoring — a dropped host shrinks
            // what allOk demands, so the sweep breaks on the first candidate (the cached/saved one,
            // tested first by construction), that winner is cached, and it wins again next run.
            val (unresolved, sweepHosts) = hosts.partition { dns[it] == DnsOutcome.UNRESOLVED }
            // Blame the resolver only when every target was definitively refused. An empty sweep set
            // for any other reason — a cold radio right after a network change, where Android's own
            // per-attempt timeout outlives our whole budget — is a failed check, not a verdict.
            if (sweepHosts.isEmpty() && unresolved.isNotEmpty()) {
                _autoTune.value = AutoTuneUiState(
                    finished = true,
                    error = app.getString(R.string.auto_tune_dns_failed),
                )
                return@launch
            }
            var lastHosts: Map<String, Boolean> = emptyMap()
            var bestHosts: Map<String, Boolean> = emptyMap()
            // Set when a candidate leaves the native engine held; see the check in the loop.
            var engineStuck = false
            val found = withContext(Dispatchers.IO) {
                var hit: StrategyTester.Strategy? = null
                var bestOkCount = 0
                var lastPort = 0
                for ((i, s) in strategies.withIndex()) {
                    // The only point a cancel can act, and deliberately so: testStrategy blocks for
                    // seconds with a native instance in hand, and nothing outside it may tear that
                    // instance down — stopProxy/forceClose reach the process-wide globals, i.e.
                    // whichever run owns them right now, so firing them from here would shoot at a
                    // VPN listener that came up between candidates. Stopping before the next launch
                    // costs at most the candidate already in flight, which tears itself down.
                    if (!isActive) break
                    // update() rather than a read-modify-write of .value: this runs on IO while the
                    // cancel arrives on the main thread, and copying a stale snapshot back would
                    // drop the flag it just set.
                    _autoTune.update { it.copy(currentLabel = app.getString(s.labelRes)) }
                    val port = freeAutoTunePort(avoid = lastPort)
                    lastPort = port
                    val res = StrategyTester.testStrategy(app, s, sweepHosts, port = port)
                    // The engine is a process-wide singleton whose busy flag is lowered only when
                    // its own main() returns — jniStopProxy and jniForceClose deliberately leave it
                    // raised. So a candidate whose loop thread outlived teardown still HOLDS the
                    // engine, and every launch after it, this sweep's next candidate and the VPN
                    // alike, is refused for the life of the process. Carrying on would score every
                    // remaining strategy dead and then persist that verdict. Stop and say so.
                    if (!res.engineReleased) {
                        engineStuck = true
                        break
                    }
                    // Count the candidate once it has actually been tested. Counted up front instead,
                    // index/total pinned the bar at 100% for the whole final candidate — minutes of a
                    // bar claiming to be done — and never let it start at 0. Incrementing here is
                    // also what makes every exit below right for free: the allOk break leaves the
                    // winner counted, and a cancel leaves the bar exactly where the user stopped it.
                    _autoTune.update { it.copy(index = i + 1) }
                    // Key by the RAW hostname. Keying by the localized label used to work only
                    // because the reader resolved the same resource in the same locale; a per-app
                    // language or an Activity-level config override yields a different string there
                    // and every service row silently degrades to "not checked".
                    val hostMap = res.hosts.associate { hr -> hr.host to hr.ok }
                    lastHosts = hostMap
                    // Keep the strategy that unblocks the MOST hosts (>=1). Requiring every host to
                    // pass in one shot was too strict: a strategy that opens YouTube but not
                    // Instagram (or a host that merely flapped on a timeout) was discarded, so the
                    // user saw "ни один метод не пробил" even though YouTube actually worked.
                    val okCount = res.hosts.count { it.ok }
                    if (okCount > bestOkCount) {
                        bestOkCount = okCount
                        hit = s
                        bestHosts = hostMap
                    }
                    if (res.allOk) break // every host we could test passed; nothing beats that
                }
                if (bestOkCount > 0) hit else null
            }
            // Checked before the winner is honoured: a stuck engine means every candidate after the
            // one that wedged scored dead for a reason that has nothing to do with its strategy, so
            // persisting a "best" picked from that run would pin the user to a bad command AND to a
            // cache entry that re-elects it. Nothing is saved; the user is told to restart the app,
            // which is genuinely the only cure — the flag lives in native memory.
            if (engineStuck) {
                _autoTune.value = AutoTuneUiState(
                    finished = true,
                    error = app.getString(R.string.auto_tune_engine_stuck),
                    hostOk = lastHosts,
                    unresolvedHosts = unresolved,
                )
                return@launch
            }
            if (found != null) {
                setByedpiCmd(found.command)
                networkCacheKey?.let { prefs().edit().putString(AUTO_TUNE_CACHE_PREFIX + it, found.command).apply() }
                _autoTune.value = AutoTuneUiState(
                    finished = true,
                    foundLabel = app.getString(found.labelRes),
                    foundCommand = found.command,
                    hostOk = bestHosts,
                    unresolvedHosts = unresolved,
                )
            } else {
                _autoTune.value = AutoTuneUiState(
                    finished = true,
                    error = app.getString(R.string.auto_tune_none_worked),
                    hostOk = lastHosts,
                    unresolvedHosts = unresolved,
                )
            }
        }
        autoTuneJob = job
        // The only place a cancelled sweep may report itself. A Job reaches its final state only
        // once its body has returned, so by the time this runs the candidate in flight has been
        // through StrategyTester's teardown; clearing `running` from cancelAutoTune instead would
        // re-open the "Tune again" button while that teardown was still under way. What it does NOT
        // establish — left unfixed here on purpose, so read it as a risk and not as a promise — is
        // that the engine is free again: that teardown is stopProxy() + join(1500) + forceClose() +
        // join(1500) and then returns whether or not the byedpi loop thread actually died, while
        // native-lib.c lowers g_proxy_running only from jniStartProxy's epilogue — jniStopProxy and
        // jniForceClose leave the flag raised deliberately. A loop that outlives both joins therefore
        // keeps the process-wide claim for the life of the process, refusing every later VPN start,
        // while this card has already declared the sweep over. Nothing this class can observe tells
        // that apart from a clean finish, and no teardown may be fired from here (see the sweep loop
        // for why), so the repair belongs one layer down: StrategyTester already knows whether the
        // loop thread returned, and once it reports that, this handler can hold `running` until it
        // has. Nothing was applied either: setByedpiCmd and the per-network cache write live past the
        // sweep's withContext, which throws instead of returning once the job is cancelled, so a
        // half-tested leader can never be saved.
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                _autoTune.value = AutoTuneUiState(
                    finished = true,
                    error = app.getString(R.string.auto_tune_cancelled),
                )
            }
        }
    }

    /**
     * Stop a sweep in flight. Cancelling the coroutine does not stop the blocking native run it is
     * inside — see the loop in [runAutoTune] for why no teardown may be fired from here — so this
     * only guarantees that no *further* candidate is launched, and the card stays up with
     * [AutoTuneUiState.cancelling] set until the one in flight has run its own teardown to the end.
     * Idempotent: a second tap finds the job already cancelled and does nothing.
     */
    fun cancelAutoTune() {
        val job = autoTuneJob ?: return
        // Nothing to stop unless a sweep is genuinely up: a second tap finds the job no longer active
        // (the first left it cancelling), and a run that has already published its verdict — the DNS
        // exit takes that route too — would only get a finished card repainted as a cancellation. The
        // same test inside update() covers the sweep landing between these two lines.
        if (!job.isActive || !_autoTune.value.running) return
        _autoTune.update { if (it.running) it.copy(cancelling = true) else it }
        job.cancel()
    }

    /**
     * Clear a finished card. Refuses while a sweep is up — the run would carry on natively behind a
     * reset UI and finish by writing a command the user thought they had dismissed. [cancelAutoTune]
     * is the way out of a running sweep.
     */
    fun dismissAutoTune() {
        if (!_autoTune.value.running) _autoTune.value = AutoTuneUiState()
    }

    /** Load installed launchable apps (label + package), built-in exclusions flagged + on top. */
    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                val self = getApplication<Application>().packageName
                val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolved = pm.queryIntentActivities(launchable, 0)
                resolved.asSequence()
                    .map { it.activityInfo.packageName }
                    .filter { it != self }
                    .distinct()
                    .map { pkg ->
                        val label = try {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        } catch (_: Exception) { pkg }
                        AppInfo(pkg = pkg, label = label, builtIn = builtInExcluded.contains(pkg))
                    }
                    .sortedWith(compareByDescending<AppInfo> { it.builtIn }.thenBy { it.label.lowercase() })
                    .toList()
            }
            _installedApps.value = apps
        }
    }

    /** Add/remove an app from the user exclusion set (built-in ones are forced on and ignored). */
    fun setExcluded(pkg: String, excluded: Boolean) {
        if (builtInExcluded.contains(pkg)) return
        val next = _excluded.value.toMutableSet()
        if (excluded) next.add(pkg) else next.remove(pkg)
        prefs().edit().putStringSet(DesyncVpnService.KEY_EXCLUDED_USER, next).apply()
        _excluded.value = next
    }

    private fun prefs() =
        getApplication<Application>().getSharedPreferences(DesyncVpnService.PREFS, Context.MODE_PRIVATE)

    private fun loadExcluded(): Set<String> =
        prefs().getStringSet(DesyncVpnService.KEY_EXCLUDED_USER, emptySet())?.toSet() ?: emptySet()

    /** What a pre-sweep lookup actually established about one host. */
    private enum class DnsOutcome {
        /** The resolver handed back addresses. */
        RESOLVED,

        /** The resolver answered, and the answer was "no such host". The only outcome that skips. */
        UNRESOLVED,

        /** No answer inside the budget, or our own check broke. Says nothing — sweep the host. */
        UNKNOWN,
    }

    /**
     * What this network was able to tell us about [hosts] before the sweep starts. Three-way on
     * purpose: skipping a target is a claim about the network, and only a definitive negative earns
     * it. A lookup that has merely not come back yet is [DnsOutcome.UNKNOWN] and gets swept exactly
     * as it was before this check existed — worst case it fails in the sweep the way it always did.
     *
     * Each lookup gets its own thread: InetAddress has no timeout knob and Android's resolver sits
     * on a dead server through its whole retry ladder, so the only way to bound this is to stop
     * waiting. Abandoning a straggler therefore has to be free, and here it is: the first lookups
     * after a network change routinely outlast this budget with netd's cache cold, and on such a
     * perfectly healthy network nothing may be skipped. Must be called off the main thread.
     */
    private fun dnsOutcomes(hosts: List<String>): Map<String, DnsOutcome> {
        val outcomes = ConcurrentHashMap<String, DnsOutcome>()
        val workers = hosts.map { host ->
            thread(name = "dns-$host", isDaemon = true) {
                // Twice before believing a refusal. On Android UnknownHostException is also what a
                // transient resolver failure looks like — EAI_AGAIN, a SERVFAIL, netd giving up —
                // and the underlying code is not readable from here (GaiException is not public
                // API). One such blip on a healthy network would drop the host from the sweep,
                // which shrinks what allOk demands, so the loop breaks on candidate #0 and caches
                // it. A second attempt that never returns is fine: the join deadline leaves the
                // host UNKNOWN, and UNKNOWN is swept.
                outcomes[host] = try {
                    resolveOnce(host)
                } catch (_: UnknownHostException) {
                    try {
                        resolveOnce(host)
                    } catch (_: UnknownHostException) {
                        DnsOutcome.UNRESOLVED // refused twice; still never means "blocked"
                    } catch (_: Throwable) {
                        DnsOutcome.UNKNOWN
                    }
                } catch (_: Throwable) {
                    DnsOutcome.UNKNOWN // SecurityException & friends: our check broke, not the name
                }
            }
        }
        val deadlineNs = System.nanoTime() + DNS_TIMEOUT_MS * 1_000_000L
        for (w in workers) {
            val leftMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (leftMs <= 0L) break // join(0) waits forever — abandon the stragglers instead
            try { w.join(leftMs) } catch (_: InterruptedException) {}
        }
        // A straggler that publishes between the loop exiting and this read is still honoured — the
        // map is live. That is harmless (a late definitive answer is no less definitive) but it is
        // not the same as "expired lookups are ignored": absent simply stays UNKNOWN, i.e. swept.
        return hosts.associateWith { outcomes[it] ?: DnsOutcome.UNKNOWN }
    }

    /** One resolver attempt. Throws [UnknownHostException] on a refusal, like the platform call. */
    private fun resolveOnce(host: String): DnsOutcome =
        if (InetAddress.getAllByName(host).isNotEmpty()) {
            DnsOutcome.RESOLVED
        } else {
            DnsOutcome.UNKNOWN // no addresses without an error: nothing was established
        }

    /**
     * Pick a loopback port the next byedpi test instance can actually bind. The old fixed rotation
     * assumed 1081..1084 were ours: if any local process (another proxy app, a debug tool) squats on
     * one, every candidate landing there fails to bind and the tuner blames the *strategy* for a
     * port problem. Same probe-then-take approach as DesyncVpnService.selectSocksPort, reimplemented
     * here because that one is private and owns the VPN's separate port set.
     *
     * [avoid] is the port the previous candidate just used. byedpi's listener can still be unwinding
     * when the next strategy starts, so we never hand back the port we just released even if the
     * probe below says it is free again — a bind that races the dying instance would misreport as a
     * failed strategy, which is exactly the confusion this function exists to remove.
     */
    private fun freeAutoTunePort(avoid: Int): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (candidate in AUTO_TUNE_PORTS) {
            if (candidate == avoid) continue
            try {
                ServerSocket(candidate, 1, loopback).use { return candidate }
            } catch (_: Exception) {
                // Something local owns this port; try the next candidate.
            }
        }
        // All fixed candidates busy — let the OS hand out an ephemeral port instead of forcing a
        // bind failure that would be attributed to the strategy under test.
        return try {
            ServerSocket(0, 1, loopback).use { it.localPort }
        } catch (_: Exception) {
            AUTO_TUNE_PORTS.firstOrNull { it != avoid } ?: AUTO_TUNE_PORTS[0]
        }
    }

    /** Stable, privacy-preserving key for the active network; never stores the Wi-Fi name itself. */
    private fun autoTuneNetworkCacheKey(): String? {
        val app = getApplication<Application>()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val locationGranted = ContextCompat.checkSelfPermission(
                    app,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
                if (!locationGranted) return null
                val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ssid = wifi.connectionInfo?.ssid?.trim('"')
                if (ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID) return null
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(ssid.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                "wifi_${digest.take(16)}"
            }
            else -> null
        }
    }

    /**
     * Repair a superseded saved command once, at the pref rather than only in memory.
     *
     * DesyncVpnService.loadPrefs also migrates, but only for the string it is about to launch — the
     * pref itself keeps the old spelling, and this class reads it raw. Left at that, the UI would
     * print one command as "current" while the engine ran another, and since every shipped -A
     * strategy now carries -T the stale text would match no preset and be relabelled a custom
     * command. In an app whose support flow is "send us your current command", showing a value the
     * engine is not running is a reporting defect, not cosmetics.
     */
    private fun migrateSavedCommand() {
        val current = _settings.value.byedpiCmd
        val migrated = ByedpiPresetCatalog.migrateCommand(current)
        if (migrated != current) setByedpiCmd(migrated)
    }

    private fun load(): DesyncSettings {
        val p = prefs()
        return DesyncSettings(
            preset = p.getString(DesyncVpnService.KEY_PRESET, DesyncVpnService.PRESET_AUTO)
                ?: DesyncVpnService.PRESET_AUTO,
            blockQuic = p.getBoolean(DesyncVpnService.KEY_BLOCK_QUIC, true),
            allApps = p.getBoolean(DesyncVpnService.KEY_ALL_APPS, true),
            byedpiCmd = p.getString(DesyncVpnService.KEY_BYEDPI_CMD, "") ?: "",
        )
    }

    /** Default byedpi command for the current preset (shown as a hint / starting point). */
    fun defaultCmdForPreset(preset: String): String =
        ByedpiPresetCatalog.commandFor(preset)

    /**
     * Migrating HERE rather than only on load is what makes "the pref, the UI and the engine hold
     * one string" actually true. This is the write the user goes through — the Apply button on the
     * command editor — so without it, typing an -A group by hand stores the inert spelling, the
     * card labels it a custom command because no shipped strategy matches, and the VPN then runs
     * something else entirely, since loadPrefs repairs it again on the way to the engine.
     */
    fun setByedpiCmd(cmd: String) {
        val migrated = ByedpiPresetCatalog.migrateCommand(cmd)
        prefs().edit().putString(DesyncVpnService.KEY_BYEDPI_CMD, migrated).apply()
        _settings.value = _settings.value.copy(byedpiCmd = migrated)
    }

    fun setPreset(preset: String) {
        // A catalog preset must win over a previously auto-tuned/manual command. Otherwise the
        // chip changes visually while the VPN still launches the old custom command.
        prefs().edit()
            .putString(DesyncVpnService.KEY_PRESET, preset)
            .remove(DesyncVpnService.KEY_BYEDPI_CMD)
            .apply()
        _settings.value = _settings.value.copy(preset = preset, byedpiCmd = "")
    }

    fun setBlockQuic(on: Boolean) {
        prefs().edit().putBoolean(DesyncVpnService.KEY_BLOCK_QUIC, on).apply()
        _settings.value = _settings.value.copy(blockQuic = on)
    }

    fun setAllApps(on: Boolean) {
        prefs().edit().putBoolean(DesyncVpnService.KEY_ALL_APPS, on).apply()
        _settings.value = _settings.value.copy(allApps = on)
    }
}
