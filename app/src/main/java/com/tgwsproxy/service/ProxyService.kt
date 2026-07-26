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
import java.net.BindException
import java.security.SecureRandom
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Colour role of a log line, decided once — when the line is created. The log pane draws the whole
 * (200-line capped) log as a single list item, so deriving this in composition meant re-scanning
 * every line for seven keywords on every arriving line.
 */
enum class LogKind { ERROR, WARNING, HANDSHAKE, FAKE_TLS, CLOUDFLARE, WS, PLAIN }

/**
 * One log line plus what the UI needs to draw and track it. [seq] never repeats, which is the only
 * thing that still changes once the log sits at its cap and the list stops growing.
 */
data class LogLine(val seq: Long, val text: String, val kind: LogKind)

/** Keyword order matters: "handshake failed" is a warning, not a handshake. */
private fun classifyLog(text: String): LogKind = when {
    text.contains("ERROR", ignoreCase = true) -> LogKind.ERROR
    text.contains("failed", ignoreCase = true) -> LogKind.WARNING
    text.contains("WARN", ignoreCase = true) -> LogKind.WARNING
    text.contains("handshake ok", ignoreCase = true) -> LogKind.HANDSHAKE
    text.contains("Fake TLS", ignoreCase = true) -> LogKind.FAKE_TLS
    text.contains("Cloudflare", ignoreCase = true) -> LogKind.CLOUDFLARE
    text.contains("WS connected", ignoreCase = true) -> LogKind.WS
    else -> LogKind.PLAIN
}

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
        val logs: List<LogLine> = emptyList(),
        val proxyLink: String = "",
        val cfDomain: String = "",
        val cfWorkerDomain: String = "",
        val fakeTlsDomain: String = "",
        // Live traffic stats for the UI.
        val bytesUp: Long = 0,
        val bytesDown: Long = 0,
        val startedAt: Long = 0,      // epoch millis when the proxy started (0 = stopped)
        val route: String = "",       // active upstream: cloudflare / direct / tcp
        /**
         * Why the last start attempt failed — already localized and actionable, null when there is
         * nothing to report. Same shape as DesyncVpnService.VpnState.error so both tabs explain a
         * refusal identically; without it a busy port only ever reached the log pane while the hero
         * flipped back to "off", which is pixel-identical to never having pressed the button.
         */
        val error: String? = null
    )

    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val binder = ProxyBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyServer: MtProtoProxyServer? = null
    private var statsJob: Job? = null

    /**
     * The teardown launched by the last [stopProxy], or null. [startProxy] waits it out before it
     * binds: stopping flips the state to "off" at once but closes the listener on a coroutine, so a
     * stop-then-start quicker than that close used to bind against our own dying socket and blame
     * the resulting BindException on some other app. Touched only from onStartCommand (main thread).
     */
    private var stopJob: Job? = null

    /**
     * Bumped by every start and every stop. A start coroutine that had to wait out a teardown
     * re-checks it before binding: if anything asked us to start or stop in the meantime, the socket
     * it was about to open would belong to nobody and would hold the port against the real one.
     */
    private val proxyGeneration = AtomicLong(0)

    /** Only ever increases; see [LogLine.seq]. */
    private val logSeq = AtomicLong(0)

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
            it.copy(
                secret = secret,
                proxyLink = buildProxyLink(host, port, secret, ftls),
                // proxy_error_bad_secret asks for exactly this rotation, so doing it has to retire
                // the message — otherwise obeying the instruction leaves it under the dial still
                // claiming the secret is broken. Any other pending failure judged the attempt that
                // used the key we just replaced, so it is equally spent.
                error = null
            )
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
        // Same constants the link builder and the UI seed themselves from; a literal here could
        // drift from them silently.
        val host = DEFAULT_HOST
        val port = DEFAULT_PORT
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
                // A fresh attempt retires the previous verdict: whatever last time failed with is
                // no longer what the screen is showing.
                error = null,
                logs = listOf(newLogLine(getString(R.string.proxy_starting)))
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

        val pendingStop = stopJob
        val generation = proxyGeneration.incrementAndGet()
        serviceScope.launch {
            // Deliberately outside the try: this only orders our own socket close before our own
            // open (the teardown never waits on us, so it cannot deadlock), and being cancelled
            // while waiting means the service is going away — not a start failure to report.
            pendingStop?.join()
            if (proxyGeneration.get() != generation) return@launch
            try {
                proxyServer = MtProtoProxyServer(
                    appContext = applicationContext,
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
                // The raw exception text stays in the log for support; the state carries the
                // version the user can act on.
                addLog(getString(R.string.error_with_message, e.message))
                _serviceState.update { it.copy(isRunning = false, error = startFailureMessage(e, port)) }
                persistRunning(false)
                releaseWakeLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Turn a start failure into an instruction. Worth distinguishing at all because the fixes are
     * unrelated: a busy port is somebody else's process and the user closes it, a malformed secret
     * is ours and the user rotates it, and everything else is a "try again" that must still carry
     * the underlying reason so a support log is not the only place it exists.
     */
    private fun startFailureMessage(e: Exception, port: Int): String = when (e) {
        // Bind refused. Covers both "Address already in use" and the EACCES spelling — either way
        // this port is not ours to listen on.
        is BindException -> getString(R.string.proxy_error_port_busy, port)
        // parseSecret's require(): the persisted secret is not even-length hex, so no client could
        // ever authenticate against it even if the socket did come up.
        is IllegalArgumentException -> getString(R.string.proxy_error_bad_secret)
        else -> getString(R.string.proxy_error_start_failed, e.message ?: e.javaClass.simpleName)
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
        // Invalidates any start still queued behind an earlier teardown: it must not bind now.
        proxyGeneration.incrementAndGet()

        // Detach the server reference and flip state to stopped immediately so the UI
        // and Quick Settings tile update without waiting on socket teardown.
        val server = proxyServer
        proxyServer = null

        _serviceState.update {
            it.copy(
                isRunning = false,
                connectionCount = 0,
                startedAt = 0,
                route = "",
                // Off is now the state the user asked for, so an old failure has nothing left to
                // explain — leaving it would park a stale complaint under a correct "off" hero.
                error = null
            )
        }
        persistRunning(false)
        releaseWakeLocks()

        // Stop the server off the main thread (stop() may block on socket/coroutine
        // shutdown), then drop the foreground service. Replaces the old
        // runBlocking(...) on the main thread, which was a classic ANR source.
        // Kept as stopJob so the next start can wait for the listener to actually be gone.
        //
        // Chained onto the previous teardown rather than replacing it. A stop that lands while a
        // start is still parked on the earlier job sees proxyServer already null, so replacing
        // would install a trivial no-op job — and the waiting start would join THAT and bind while
        // the real socket is still closing, which is the exact race the join was added to remove.
        val previous = stopJob
        stopJob = serviceScope.launch {
            previous?.join()
            try { server?.stop() } catch (_: Exception) {}
            // A start that raced in already owns the foreground service and is waiting on this very
            // job; tearing the notification down and stopping ourselves here would kill the run the
            // user just asked for.
            if (!_serviceState.value.isRunning) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
            val cb = object : ConnectivityManager.NetworkCallback() {
                // networkHandle is a stable per-network id (API 23+); network.toString()
                // isn't contractually stable and triggered spurious "network changed" events.
                override fun onAvailable(network: Network) = onNetworkChanged(network.networkHandle.toString())
            }
            networkCallback = cb
            cm.registerDefaultNetworkCallback(cb)
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

    /**
     * Stamp a line with its sequence number and colour role. Called outside the state update on
     * purpose — update() re-runs its lambda under contention, and a line has to be stamped exactly
     * once rather than renumbered and re-classified per retry.
     */
    private fun newLogLine(text: String) = LogLine(logSeq.incrementAndGet(), text, classifyLog(text))

    private fun addLog(line: String) {
        val timestamp = LocalTime.now().format(logTimeFormatter)
        val entry = newLogLine("[$timestamp] $line")
        _serviceState.update { state ->
            state.copy(logs = (state.logs + entry).takeLast(200))
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
                description = getString(R.string.proxy_notification_channel_desc)
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
