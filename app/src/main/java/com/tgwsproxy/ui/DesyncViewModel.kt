package com.tgwsproxy.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tgwsproxy.net.HelloProbe
import com.tgwsproxy.net.StrategyTester
import com.tgwsproxy.vpn.DesyncVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DesyncSettings(
    val preset: String = DesyncVpnService.PRESET_AUTO,
    val blockQuic: Boolean = true,
    val allApps: Boolean = false,
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
)

/**
 * UI state holder for the "Разблокировка" tab. Live runtime state (running / flows / bytes) comes
 * straight from [DesyncVpnService.state]; user settings are persisted to prefs and re-read so they
 * survive restarts. Starting the VPN itself is driven from the Activity (needs system consent).
 */
class DesyncViewModel(application: Application) : AndroidViewModel(application) {

    val vpnState: StateFlow<DesyncVpnService.VpnState> = DesyncVpnService.state

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DesyncSettings> = _settings.asStateFlow()

    private val _probe = MutableStateFlow(ProbeUiState())
    val probe: StateFlow<ProbeUiState> = _probe.asStateFlow()

    private val _autoTune = MutableStateFlow(AutoTuneUiState())
    val autoTune: StateFlow<AutoTuneUiState> = _autoTune.asStateFlow()

    private val targets = listOf(
        "www.youtube.com" to "YouTube",
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
        val strategies = StrategyTester.STRATEGIES
        _autoTune.value = AutoTuneUiState(running = true, total = strategies.size)
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                var hit: StrategyTester.Strategy? = null
                for ((i, s) in strategies.withIndex()) {
                    _autoTune.value = _autoTune.value.copy(index = i + 1, currentLabel = s.label)
                    val res = StrategyTester.testStrategy(s, hosts, port = 1081 + (i % 4))
                    if (res.allOk) { hit = s; break }
                }
                hit
            }
            if (found != null) {
                setByedpiCmd(found.command)
                _autoTune.value = AutoTuneUiState(
                    finished = true,
                    foundLabel = found.label,
                    foundCommand = found.command,
                )
            } else {
                _autoTune.value = AutoTuneUiState(
                    finished = true,
                    error = "Ни один метод не пробил блокировку. Попробуй вручную в продвинутых настройках."
                )
            }
        }
    }

    fun dismissAutoTune() {
        if (!_autoTune.value.running) _autoTune.value = AutoTuneUiState()
    }

    private fun prefs() =
        getApplication<Application>().getSharedPreferences(DesyncVpnService.PREFS, Context.MODE_PRIVATE)

    private fun load(): DesyncSettings {
        val p = prefs()
        return DesyncSettings(
            preset = p.getString(DesyncVpnService.KEY_PRESET, DesyncVpnService.PRESET_AUTO)
                ?: DesyncVpnService.PRESET_AUTO,
            blockQuic = p.getBoolean(DesyncVpnService.KEY_BLOCK_QUIC, true),
            allApps = p.getBoolean(DesyncVpnService.KEY_ALL_APPS, false),
            byedpiCmd = p.getString(DesyncVpnService.KEY_BYEDPI_CMD, "") ?: "",
        )
    }

    /** Default byedpi command for the current preset (shown as a hint / starting point). */
    fun defaultCmdForPreset(preset: String): String =
        DesyncVpnService.presetToByedpiArgs(preset)

    fun setByedpiCmd(cmd: String) {
        prefs().edit().putString(DesyncVpnService.KEY_BYEDPI_CMD, cmd).apply()
        _settings.value = _settings.value.copy(byedpiCmd = cmd)
    }

    fun setPreset(preset: String) {
        prefs().edit().putString(DesyncVpnService.KEY_PRESET, preset).apply()
        _settings.value = _settings.value.copy(preset = preset)
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
