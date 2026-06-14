package com.tgwsproxy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.tgwsproxy.ui.MainScreen
import com.tgwsproxy.ui.theme.TgWsProxyTheme
import com.tgwsproxy.vpn.DesyncVpnService

class MainActivity : ComponentActivity() {

    // Result is ignored: if the user denies, the proxy still runs as a foreground service,
    // just without a visible status notification. We don't block on the grant.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // System VPN consent dialog ("Разрешить Jevio создать VPN-подключение?"). On approval we
    // actually start the desync VPN service.
    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) startDesyncVpn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        setContent {
            TgWsProxyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onEnableVpn = { requestVpnConsent() },
                        onDisableVpn = { stopDesyncVpn() }
                    )
                }
            }
        }
    }

    /** Ask for VPN consent (once granted, Android remembers it) then start the service. */
    private fun requestVpnConsent() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnConsentLauncher.launch(prepare)
        } else {
            startDesyncVpn()
        }
    }

    private fun startDesyncVpn() {
        val intent = Intent(this, DesyncVpnService::class.java).apply {
            action = DesyncVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopDesyncVpn() {
        val intent = Intent(this, DesyncVpnService::class.java).apply {
            action = DesyncVpnService.ACTION_STOP
        }
        startService(intent)
    }

    /**
     * On Android 13+ (API 33) the foreground-service status notification is silently
     * suppressed unless POST_NOTIFICATIONS is granted at runtime — declaring it in the
     * manifest is not enough. Request it once on launch so "Jevio Unblocker включён"
     * actually shows up. On older versions the permission is granted at install time.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
