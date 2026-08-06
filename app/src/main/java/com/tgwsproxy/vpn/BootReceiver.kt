package com.tgwsproxy.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.tgwsproxy.service.ProxyService

/**
 * Re-enables whichever of the two modules was running before a reboot — so the user (or grandma)
 * doesn't have to reopen the app and toggle it back on.
 *
 * The two are restored independently: they are separate features (a local MTProto proxy and the
 * desync VPN), each with its own persisted flag, and they can be on in any combination. Neither
 * start may prevent the other's, which is why they are attempted in their own try blocks — see
 * [startSafely].
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // Only the protected BOOT_COMPLETED broadcast (deliverable by the system only). We
        // intentionally drop the unprotected QUICKBOOT_POWERON, which any app could spoof to
        // start the VPN service without the user's involvement.
        if (action != Intent.ACTION_BOOT_COMPLETED) return

        restoreProxy(context)
        restoreVpn(context)
    }

    /**
     * The MTProto proxy binds 127.0.0.1:1443 and needs no system consent, so the only precondition
     * is the flag [ProxyService] itself writes. Restoring it matters more than it looks: Telegram
     * keeps the proxy configured across a reboot and will keep dialling a port nothing is listening
     * on, which surfaces as "connecting…" with no hint that the cause is on this device.
     */
    private fun restoreProxy(context: Context) {
        val wasRunning = context
            .getSharedPreferences(ProxyService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(ProxyService.KEY_RUNNING, false)
        if (!wasRunning) return

        startSafely(context, Intent(context, ProxyService::class.java).apply {
            this.action = ProxyService.ACTION_START
        })
    }

    /**
     * The VPN additionally needs consent to already exist: `prepare() != null` means we would have
     * to show the system dialog, and a boot broadcast has no UI to show it from.
     */
    private fun restoreVpn(context: Context) {
        val wasRunning = context
            .getSharedPreferences(DesyncVpnService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(DesyncVpnService.KEY_VPN_RUNNING, false)
        if (!wasRunning) return

        if (VpnService.prepare(context) != null) return

        startSafely(context, Intent(context, DesyncVpnService::class.java).apply {
            this.action = DesyncVpnService.ACTION_START
        })
    }

    /**
     * BOOT_COMPLETED is one of the exemptions that lets an app start a foreground service from the
     * background on Android 12+, but the guarantee is not absolute: OEM power managers and an app
     * that boots into a restricted bucket can still refuse with ForegroundServiceStartNotAllowed,
     * and an unhandled throw here would abort the receiver — losing the *other* module's restore
     * along with this one. There is nothing to report from a boot broadcast, so a refusal simply
     * leaves that module off, exactly as if the flag had been false.
     */
    private fun startSafely(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Throwable) {
        }
    }
}
