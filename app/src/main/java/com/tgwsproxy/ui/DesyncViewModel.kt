package com.tgwsproxy.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tgwsproxy.net.HelloProbe
import com.tgwsproxy.vpn.DesyncVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DesyncSettings(
    val preset: String = DesyncVpnService.PRESET_TLSREC,
    val blockQuic: Boolean = true,
    val allApps: Boolean = false,
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
            // If a method clearly works for both, gently steer the preset toward it.
            val best = results.firstNotNullOfOrNull { it.bestPreset }
            if (best != null && results.all { it.anyPass }) setPreset(best)
        }
    }

    private fun prefs() =
        getApplication<Application>().getSharedPreferences(DesyncVpnService.PREFS, Context.MODE_PRIVATE)

    private fun load(): DesyncSettings {
        val p = prefs()
        return DesyncSettings(
            preset = p.getString(DesyncVpnService.KEY_PRESET, DesyncVpnService.PRESET_TLSREC)
                ?: DesyncVpnService.PRESET_TLSREC,
            blockQuic = p.getBoolean(DesyncVpnService.KEY_BLOCK_QUIC, true),
            allApps = p.getBoolean(DesyncVpnService.KEY_ALL_APPS, false),
        )
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
