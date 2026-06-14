package com.tgwsproxy.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.tgwsproxy.vpn.DesyncVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DesyncSettings(
    val preset: String = DesyncVpnService.PRESET_TLSREC,
    val blockQuic: Boolean = true,
    val allApps: Boolean = false,
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
