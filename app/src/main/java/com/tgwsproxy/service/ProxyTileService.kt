package com.tgwsproxy.service

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tgwsproxy.R

/**
 * Quick Settings tile that lets the user start/stop the proxy straight from the
 * notification shade, without opening the app. The tile mirrors the live running
 * state via [ProxyService.KEY_RUNNING] in SharedPreferences; the service nudges a
 * refresh through [TileService.requestListeningState] whenever that state changes.
 */
class ProxyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val running = isRunning()
        val intent = Intent(this, ProxyService::class.java).apply {
            action = if (running) ProxyService.ACTION_STOP else ProxyService.ACTION_START
        }
        if (running) {
            startService(intent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        // Optimistically flip the tile; the service also requests a refresh once
        // the real state settles.
        setTileState(!running)
    }

    private fun isRunning(): Boolean =
        getSharedPreferences(ProxyService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(ProxyService.KEY_RUNNING, false)

    private fun updateTile() = setTileState(isRunning())

    private fun setTileState(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_shield)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (running) R.string.proxy_running else R.string.proxy_stopped
            )
        }
        tile.updateTile()
    }
}
