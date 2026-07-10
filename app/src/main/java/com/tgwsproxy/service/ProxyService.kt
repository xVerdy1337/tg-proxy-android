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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tgwsproxy.MainActivity
import com.tgwsproxy.R
import com.tgwsproxy.proxy.MtProtoProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        const val KEY_CF_DOMAIN = "cf_domain"
        const val KEY_CF_WORKER_DOMAIN = "cf_worker_domain"
        const val KEY_FAKE_TLS_DOMAIN = "fake_tls_domain"
        const val KEY_SECRET = "proxy_secret"
        // Shared with ProxyTileService so the Quick Settings tile reflects live state.
        const val KEY_RUNNING = "proxy_running"

        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1443

        /**
         * Build the tg:// proxy link. Pure helper so the UI can render the link
         * immediately from persisted prefs without waiting for the service to bind.
         */
        fun buildProxyLink(host: String, port: Int, secret: String, fakeTlsDomain: String = ""): String {
            val domain = fakeTlsDomain.trim()
            return if (domain.isNotEmpty()) {
                val domainHex = domain.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
                "tg://proxy?server=$host&port=$port&secret=ee$secret$domainHex"
            } else {
                "tg://proxy?server=$host&port=$port&secret=dd$secret"
            }
        }
    }

    data class ServiceState(
        val isRunning: Boolean = false,
        val host: String = "127.0.0.1",
        val port: Int = 1443,
        val secret: String = "",
        val connectionCount: Int = 0,
        val logs: List<String> = emptyList(),
        val proxyLink: String = "",
        val cfDomain: String = "",
        val cfWorkerDomain: String = "",
        val fakeTlsDomain: String = "",
        // Live traffic stats for the UI.
        val bytesUp: Long = 0,
        val bytesDown: Long = 0,
        val startedAt: Long = 0,      // epoch millis when the proxy started (0 = stopped)
        val route: String = ""        // active upstream: cloudflare / direct / tcp
    )

    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val binder = ProxyBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyServer: MtProtoProxyServer? = null
    private var statsJob: Job? = null

    // True while the UI is bound to us. The 1s stats pump is only useful when someone is
    // actually watching the screen; when the app is closed we poll far less often to avoid
    // waking the CPU every second 24/7 (battery win on an always-on background proxy).
    @Volatile private var uiBound = false

    // Watches Wi-Fi ↔ mobile transitions so active sessions reconnect on the new network.
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetworkId: String? = null
    @Volatile private var lastNetworkChangeAt: Long = 0

    // Wake locks keep the CPU and Wi-Fi radio alive while the proxy is running,
    // so the local relay survives screen-off / Doze instead of silently dying.
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    inner class ProxyBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    override fun onBind(intent: Intent?): IBinder {
        uiBound = true
        startStatsPump()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        uiBound = false
        statsJob?.cancel()
        statsJob = null
        return true // allow onRebind when the UI comes back
    }

    override fun onRebind(intent: Intent?) {
        uiBound = true
        startStatsPump()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Restore the saved Cloudflare-proxy domain so the UI reflects it on launch.
        val savedDomain = prefs.getString(KEY_CF_DOMAIN, "") ?: ""
        val savedWorker = prefs.getString(KEY_CF_WORKER_DOMAIN, "") ?: ""
        val savedFakeTls = prefs.getString(KEY_FAKE_TLS_DOMAIN, "") ?: ""
        // Restore (or create) the stable secret so the tg:// link stays the same
        // across stop/start — the user no longer has to re-add the proxy each time.
        val secret = getOrCreateSecret()
        val host = _serviceState.value.host
        val port = _serviceState.value.port
        _serviceState.update {
            it.copy(
                cfDomain = savedDomain,
                cfWorkerDomain = savedWorker,
                fakeTlsDomain = savedFakeTls,
                secret = secret,
                proxyLink = buildProxyLink(host, port, secret, savedFakeTls)
            )
        }
    }

    /**
     * Build the tg:// proxy link. With a Fake-TLS domain we emit an `ee` secret
     * (secret + hex(domain)) so Telegram wraps the stream in TLS-to-that-domain; otherwise
     * we use the `dd` (secure/padded) secret on the raw path.
     */
    private fun buildProxyLink(host: String, port: Int, secret: String, fakeTlsDomain: String = ""): String =
        Companion.buildProxyLink(host, port, secret, fakeTlsDomain)

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
        val ftls = _serviceState.value.fakeTlsDomain
        _serviceState.update {
            it.copy(secret = secret, proxyLink = buildProxyLink(host, port, secret, ftls))
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

    /** Persist the user's Cloudflare Worker domain(s). Takes effect on the next start. */
    fun setCfWorkerDomain(domain: String) {
        val cleaned = domain.trim()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CF_WORKER_DOMAIN, cleaned)
            .apply()
        _serviceState.update { it.copy(cfWorkerDomain = cleaned) }
    }

    /**
     * Persist the Fake-TLS masking domain and rebuild the link (ee/dd switches with it).
     * Takes effect on the next start; the user must re-add the new link in Telegram.
     */
    fun setFakeTlsDomain(domain: String) {
        val cleaned = domain.trim()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FAKE_TLS_DOMAIN, cleaned)
            .apply()
        val host = _serviceState.value.host
        val port = _serviceState.value.port
        val secret = _serviceState.value.secret
        _serviceState.update {
            it.copy(
                fakeTlsDomain = cleaned,
                proxyLink = buildProxyLink(host, port, secret, cleaned)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
            null -> {
                // System recreated us (START_STICKY redelivers a null intent). Re-launch
                // the proxy if we believe it should be running; otherwise reconcile the
                // stale flag and stand down so we don't sit as a zombie foreground service.
                val shouldRun = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_RUNNING, false)
                if (shouldRun && !_serviceState.value.isRunning) startProxy()
                else if (!shouldRun) stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startProxy() {
        if (_serviceState.value.isRunning) return

        val secret = getOrCreateSecret()
        val host = "127.0.0.1"
        val port = 1443
        val fakeTlsDomain = _serviceState.value.fakeTlsDomain
        val proxyLink = buildProxyLink(host, port, secret, fakeTlsDomain)

        _serviceState.update {
            it.copy(
                isRunning = true,
                host = host,
                port = port,
                secret = secret,
                proxyLink = proxyLink,
                connectionCount = 0,
                bytesUp = 0,
                bytesDown = 0,
                startedAt = System.currentTimeMillis(),
                route = "",
                logs = listOf("Прокси запускается...")
            )
        }
        persistRunning(true)
        acquireWakeLocks()
        registerNetworkCallback()

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
                    },
                    cfDomain = _serviceState.value.cfDomain,
                    cfWorkerDomain = _serviceState.value.cfWorkerDomain,
                    fakeTlsDomain = fakeTlsDomain
                )
                proxyServer?.start()
            } catch (e: Exception) {
                addLog("Ошибка: ${e.message}")
                _serviceState.update { it.copy(isRunning = false) }
                persistRunning(false)
                releaseWakeLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    /**
     * Hold a partial CPU wake lock plus a high-performance Wi-Fi lock while the
     * proxy runs. Without these the system can park the CPU / Wi-Fi radio on
     * screen-off, which drops the relay until the user reopens the app.
     */
    private fun acquireWakeLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Jevio:ProxyWakeLock"
                ).apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                // FULL_HIGH_PERF keeps Wi-Fi awake for the relay without the extra power draw of
                // FULL_LOW_LATENCY (that mode is meant for gaming/voice and pins the radio in a
                // high-power, low-latency state — overkill for a mostly-idle proxy).
                @Suppress("DEPRECATION")
                val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "Jevio:ProxyWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (_: Exception) {
            // Wake locks are best-effort; never let them crash the service.
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun stopProxy() {
        statsJob?.cancel()
        statsJob = null
        unregisterNetworkCallback()

        // Detach the server reference and flip state to stopped immediately so the UI
        // and Quick Settings tile update without waiting on socket teardown.
        val server = proxyServer
        proxyServer = null

        _serviceState.update {
            it.copy(
                isRunning = false,
                connectionCount = 0,
                startedAt = 0,
                route = ""
            )
        }
        persistRunning(false)
        releaseWakeLocks()

        // Stop the server off the main thread (stop() may block on socket/coroutine
        // shutdown), then drop the foreground service. Replaces the old
        // runBlocking(...) on the main thread, which was a classic ANR source.
        serviceScope.launch {
            try { server?.stop() } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Poll live counters only while the UI is visible; traffic forwarding does not depend on it. */
    private fun startStatsPump() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && uiBound) {
                val server = proxyServer
                if (server != null) {
                    val up = server.bytesUp.get()
                    val down = server.bytesDown.get()
                    val route = server.lastRoute
                    _serviceState.update { it.copy(bytesUp = up, bytesDown = down, route = route) }
                }
                delay(1000)
            }
        }
    }

    /**
     * Listen for the device's default network changing (Wi-Fi ↔ mobile). When the active
     * network actually changes we re-acquire the Wi-Fi lock and tell the proxy to drop its
     * stale upstream sockets so Telegram reconnects instantly over the new link.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = cm
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                // networkHandle is a stable per-network id (API 23+); network.toString()
                // isn't contractually stable and triggered spurious "network changed" events.
                // NET_CAPABILITY_VALIDATED means we only react once the link really has internet.
                override fun onAvailable(network: Network) = onNetworkChanged(network.networkHandle.toString())
                override fun onLost(network: Network) = onNetworkChanged("lost")
            }
            networkCallback = cb
            cm.registerNetworkCallback(request, cb)
        } catch (_: Exception) {
            // Some OEMs throttle callback registrations; reconnect is best-effort.
        }
    }

    private fun onNetworkChanged(id: String) {
        if (!_serviceState.value.isRunning) return
        val now = System.currentTimeMillis()
        // Debounce: ignore duplicate callbacks and bursts within 1.5s.
        if (id == lastNetworkId && now - lastNetworkChangeAt < 1500) return
        lastNetworkId = id
        lastNetworkChangeAt = now
        serviceScope.launch {
            // Small settle delay so the new network is actually usable before redialing.
            delay(700)
            acquireWakeLocks()
            try { proxyServer?.resetConnections() } catch (_: Exception) {}
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
        lastNetworkId = null
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
                "Jevio Unblocker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус работы прокси Jevio Unblocker"
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
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Tapping "Остановить прокси" in the shade stops the proxy without opening the app.
        val stopIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

                val statusLine = getString(R.string.proxy_notification_text)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.proxy_notification_title))
            .setContentText(statusLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusLine))
            .setSmallIcon(R.drawable.ic_tile_shield)
            .setColor(0xFF22C55E.toInt())
            .setColorized(true)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.notification_disable), stopPendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }


    override fun onDestroy() {
        super.onDestroy()
        // Safety net: make sure locks are gone even if onDestroy hits before stopProxy.
        releaseWakeLocks()
        statsJob?.cancel()
        unregisterNetworkCallback()
        // proxyServer already stopped in stopProxy(); cancel remaining coroutines
        serviceScope.cancel()
    }
}
