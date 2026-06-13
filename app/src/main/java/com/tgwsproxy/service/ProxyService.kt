package com.tgwsproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.service.quicksettings.TileService
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tgwsproxy.MainActivity
import com.tgwsproxy.R
import com.tgwsproxy.proxy.MtProtoProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ProxyService : Service() {

    companion object {
        const val ACTION_START = "com.tgwsproxy.action.START"
        const val ACTION_STOP = "com.tgwsproxy.action.STOP"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "proxy_channel"
        const val PREFS = "tgwsproxy_prefs"
        private const val KEY_CF_DOMAIN = "cf_domain"
        private const val KEY_SECRET = "proxy_secret"
        // Shared with ProxyTileService so the Quick Settings tile reflects live state.
        const val KEY_RUNNING = "proxy_running"
    }

    data class ServiceState(
        val isRunning: Boolean = false,
        val host: String = "127.0.0.1",
        val port: Int = 1443,
        val secret: String = "",
        val connectionCount: Int = 0,
        val logs: List<String> = emptyList(),
        val proxyLink: String = "",
        val cfDomain: String = ""
    )

    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val binder = ProxyBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyServer: MtProtoProxyServer? = null

    inner class ProxyBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Restore the saved Cloudflare-proxy domain so the UI reflects it on launch.
        val savedDomain = prefs.getString(KEY_CF_DOMAIN, "") ?: ""
        // Restore (or create) the stable secret so the tg:// link stays the same
        // across stop/start — the user no longer has to re-add the proxy each time.
        val secret = getOrCreateSecret()
        val host = _serviceState.value.host
        val port = _serviceState.value.port
        _serviceState.update {
            it.copy(
                cfDomain = savedDomain,
                secret = secret,
                proxyLink = buildProxyLink(host, port, secret)
            )
        }
    }

    private fun buildProxyLink(host: String, port: Int, secret: String): String =
        "tg://proxy?server=$host&port=$port&secret=dd$secret"

    /** Returns the persisted secret, generating and saving one on first run. */
    private fun getOrCreateSecret(): String {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SECRET, "") ?: ""
        if (saved.isNotEmpty()) return saved
        val secret = generateSecret()
        prefs.edit().putString(KEY_SECRET, secret).apply()
        return secret
    }

    /**
     * Rotate the secret on demand. Only allowed while stopped — the new key
     * needs a proxy restart and the user must re-add the link in Telegram.
     */
    fun regenerateSecret() {
        if (_serviceState.value.isRunning) return
        val secret = generateSecret()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SECRET, secret)
            .apply()
        val host = _serviceState.value.host
        val port = _serviceState.value.port
        _serviceState.update {
            it.copy(secret = secret, proxyLink = buildProxyLink(host, port, secret))
        }
    }

    /** Persist the user's Cloudflare-proxy domain. Takes effect on the next start. */
    fun setCfDomain(domain: String) {
        val cleaned = domain.trim()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CF_DOMAIN, cleaned)
            .apply()
        _serviceState.update { it.copy(cfDomain = cleaned) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
        }
        return START_STICKY
    }

    private fun startProxy() {
        if (_serviceState.value.isRunning) return

        val secret = getOrCreateSecret()
        val host = "127.0.0.1"
        val port = 1443
        val proxyLink = buildProxyLink(host, port, secret)

        _serviceState.update {
            it.copy(
                isRunning = true,
                host = host,
                port = port,
                secret = secret,
                proxyLink = proxyLink,
                connectionCount = 0,
                logs = listOf("Прокси запускается...")
            )
        }
        persistRunning(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        serviceScope.launch {
            try {
                proxyServer = MtProtoProxyServer(
                    host = host,
                    port = port,
                    secret = secret,
                    onLog = { logLine -> addLog(logLine) },
                    onConnectionChange = { count ->
                        _serviceState.update { it.copy(connectionCount = count) }
                        updateNotification()
                    },
                    cfDomain = _serviceState.value.cfDomain
                )
                proxyServer?.start()
            } catch (e: Exception) {
                addLog("Ошибка: ${e.message}")
                _serviceState.update { it.copy(isRunning = false) }
                persistRunning(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun stopProxy() {
        // Block until the server fully stops before tearing down the service
        runBlocking(Dispatchers.IO) {
            try {
                proxyServer?.stop()
                proxyServer = null
            } catch (_: Exception) {}
        }

        _serviceState.update {
            it.copy(
                isRunning = false,
                connectionCount = 0
            )
        }
        persistRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Persist the running flag and nudge the Quick Settings tile to refresh, so the
     * tile in the notification shade stays in sync even while the app is closed.
     */
    private fun persistRunning(running: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, running)
            .apply()
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, ProxyTileService::class.java)
            )
        } catch (_: Exception) {
            // Tile may be unavailable on this device; ignore.
        }
    }

    fun clearLogs() {
        _serviceState.update { it.copy(logs = emptyList()) }
    }

    private val logTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun addLog(line: String) {
        val timestamp = LocalTime.now().format(logTimeFormatter)
        _serviceState.update { state ->
            val newLogs = (state.logs + "[$timestamp] $line").takeLast(200)
            state.copy(logs = newLogs)
        }
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TG WS Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о работе прокси"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Tapping "Остановить прокси" in the shade stops the proxy without opening the app.
        val stopIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val count = _serviceState.value.connectionCount
        val contentText = if (count > 0) {
            "Активных подключений: $count"
        } else {
            getString(R.string.proxy_notification_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.proxy_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.stop_proxy), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        // proxyServer already stopped in stopProxy(); cancel remaining coroutines
        serviceScope.cancel()
    }
}
