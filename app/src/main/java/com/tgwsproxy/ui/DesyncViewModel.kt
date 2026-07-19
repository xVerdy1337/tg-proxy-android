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
import com.tgwsproxy.net.HelloProbe
import com.tgwsproxy.net.StrategyTester
import com.tgwsproxy.vpn.ByedpiPresetCatalog
import com.tgwsproxy.vpn.DesyncVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

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
    val display: String,
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
    val index: Int = 0,
    val total: Int = 0,
    val currentLabel: String = "",
    val finished: Boolean = false,
    val foundLabel: String? = null,
    val foundCommand: String? = null,
    val error: String? = null,
    /** Per-host TLS-handshake result from the last/winning strategy (host → reachable). */
    val hostOk: Map<String, Boolean> = emptyMap(),
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
        const val AUTO_TUNE_CACHE_PREFIX = "auto_tune_cache_"
    }

    val vpnState: StateFlow<DesyncVpnService.VpnState> = DesyncVpnService.state

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DesyncSettings> = _settings.asStateFlow()

    private val _probe = MutableStateFlow(ProbeUiState())
    val probe: StateFlow<ProbeUiState> = _probe.asStateFlow()

    private val _autoTune = MutableStateFlow(AutoTuneUiState())
    val autoTune: StateFlow<AutoTuneUiState> = _autoTune.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _excluded = MutableStateFlow(loadExcluded())
    val excluded: StateFlow<Set<String>> = _excluded.asStateFlow()

    val builtInExcluded: Set<String> = DesyncVpnService.EXCLUDED_APPS.toSet()

    private val targets = listOf(
        "www.youtube.com" to "YouTube",
        "redirector.googlevideo.com" to "YouTube (видео)",
        "www.instagram.com" to "Instagram",
    )

    /**
     * Directly test (no VPN needed) whether each method bypasses the provider DPI for the target
     * services. Best run with the VPN OFF so it measures the raw network + method efficacy.
     */
    fun runProbe() {
        if (_probe.value.checking) return
        _probe.value = ProbeUiState(checking = true)
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                targets.map { (host, display) ->
                    ServiceProbe(
                        host = host,
                        display = display,
                        plain = HelloProbe.probe(host, method = HelloProbe.Method.PLAIN).outcome,
                        tlsrec = HelloProbe.probe(host, method = HelloProbe.Method.TLSREC).outcome,
                        split = HelloProbe.probe(host, method = HelloProbe.Method.SPLIT).outcome,
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
     * keep the first one that completes a TLS handshake to all targets. On success it's saved as the
     * active byedpi command. Requires the VPN to be OFF (engine allows one instance at a time).
     */
    fun runAutoTune() {
        if (_autoTune.value.running) return
        if (vpnState.value.isRunning) {
            _autoTune.value = AutoTuneUiState(
                finished = true,
                error = "Сначала выключи VPN — подбор использует движок монопольно."
            )
            return
        }
        val hosts = targets.map { it.first }
        val networkCacheKey = autoTuneNetworkCacheKey()
        val cached = networkCacheKey?.let { prefs().getString(AUTO_TUNE_CACHE_PREFIX + it, null) }
        // Test the currently-saved command first (instant if it still works), then the curated list.
        val saved = _settings.value.byedpiCmd.trim()
        val strategies = buildList {
            if (!cached.isNullOrEmpty()) {
                add(StrategyTester.Strategy(cached, StrategyTester.labelForCommand(cached) ?: "cached"))
            }
            if (saved.isNotEmpty() && saved != cached) {
                add(StrategyTester.Strategy(saved, StrategyTester.labelForCommand(saved) ?: "текущая команда"))
            }
            addAll(StrategyTester.STRATEGIES.filter { it.command != saved && it.command != cached })
        }
        _autoTune.value = AutoTuneUiState(running = true, total = strategies.size)
        viewModelScope.launch {
            var lastHosts: Map<String, Boolean> = emptyMap()
            var bestHosts: Map<String, Boolean> = emptyMap()
            val found = withContext(Dispatchers.IO) {
                var hit: StrategyTester.Strategy? = null
                var bestOkCount = 0
                for ((i, s) in strategies.withIndex()) {
                    _autoTune.value = _autoTune.value.copy(index = i + 1, currentLabel = s.label)
                    val res = StrategyTester.testStrategy(s, hosts, port = 1081 + (i % 4))
                    val hostMap = res.hosts.associate { hr ->
                        (targets.firstOrNull { it.first == hr.host }?.second ?: hr.host) to hr.ok
                    }
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
                    if (res.allOk) break // can't beat all-hosts-pass; stop early
                }
                if (bestOkCount > 0) hit else null
            }
            if (found != null) {
                setByedpiCmd(found.command)
                networkCacheKey?.let { prefs().edit().putString(AUTO_TUNE_CACHE_PREFIX + it, found.command).apply() }
                _autoTune.value = AutoTuneUiState(
                    finished = true,
                    foundLabel = found.label,
                    foundCommand = found.command,
                    hostOk = bestHosts,
                )
            } else {
                _autoTune.value = AutoTuneUiState(
                    finished = true,
                    error = "Ни один метод не пробил блокировку. Попробуй вручную в продвинутых настройках.",
                    hostOk = lastHosts,
                )
            }
        }
    }

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

    fun setByedpiCmd(cmd: String) {
        prefs().edit().putString(DesyncVpnService.KEY_BYEDPI_CMD, cmd).apply()
        _settings.value = _settings.value.copy(byedpiCmd = cmd)
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
