package com.tgwsproxy.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/**
 * Re-enables the desync VPN after a reboot if it was running before — so the user (or grandma)
 * doesn't have to reopen the app and toggle it back on. We only auto-start when:
 *   1. the VPN was running when the device last shut down (KEY_VPN_RUNNING), and
 *   2. VPN consent was already granted (VpnService.prepare returns null) — we can't show the
 *      system consent dialog from a boot broadcast.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        val wasRunning = context
            .getSharedPreferences(DesyncVpnService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(DesyncVpnService.KEY_VPN_RUNNING, false)
        if (!wasRunning) return

        // Consent must already exist; prepare() != null means we'd need the system dialog.
        if (VpnService.prepare(context) != null) return

        val start = Intent(context, DesyncVpnService::class.java).apply {
            this.action = DesyncVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(context, start)
    }
}
