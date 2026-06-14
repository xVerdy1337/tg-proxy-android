package com.tgwsproxy.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tgwsproxy.MainActivity
import com.tgwsproxy.R
import com.tgwsproxy.vpn.DesyncVpnService

/**
 * Quick Settings tile to toggle the DPI-bypass VPN straight from the notification shade.
 *
 * Distinct from [ProxyTileService], which controls the legacy SOCKS proxy. Starting a VpnService
 * needs user consent: if it hasn't been granted yet we open the app so the system dialog can show;
 * once granted, the tile starts/stops the service directly.
 */
class DesyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        setTileState(isRunning())
    }

    override fun onClick() {
        super.onClick()
        if (isRunning()) {
            // STOP stays on startService (not startForegroundService): if prefs are stale and no
            // service is alive, a foreground start would be obligated to call startForeground()
            // within 5s and would crash. The tile re-syncs from real state via
            // requestListeningState() fired in DesyncVpnService.persistRunning().
            runCatching {
                startService(Intent(this, DesyncVpnService::class.java).apply { action = DesyncVpnService.ACTION_STOP })
            }
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            // Need the system consent dialog — route through the app.
            val open = Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(open)
            }
        } else {
            val start = Intent(this, DesyncVpnService::class.java).apply { action = DesyncVpnService.ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(start) else startService(start)
        }
    }

    private fun isRunning(): Boolean =
        getSharedPreferences(DesyncVpnService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(DesyncVpnService.KEY_VPN_RUNNING, false)

    private fun setTileState(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Разблокировка"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_shield)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "Включена" else "Выключена"
        }
        tile.updateTile()
    }
}
