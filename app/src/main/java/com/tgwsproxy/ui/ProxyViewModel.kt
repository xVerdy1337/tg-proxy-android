package com.tgwsproxy.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tgwsproxy.R
import com.tgwsproxy.service.LogKind
import com.tgwsproxy.service.LogLine
import com.tgwsproxy.service.ProxyService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(initialStateFromPrefs())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

    /**
     * Seed the UI synchronously from persisted prefs so the very first frame already shows
     * the correct status. Without this, every cold open started with `isLoading = true` and
     * flashed the full-screen "connecting" spinner for the 1–2s it takes the service to bind
     * and emit — which looked like the proxy briefly dropping into a connecting state.
     * The real service state reconciles this within a moment once the binding completes.
     *
     * Nothing seeds [ProxyUiState.error] on purpose: a failed start is a fact about one attempt in
     * one process, never persisted, so the first frame after a cold open has nothing to report and
     * must not resurrect a complaint the user already walked away from.
     */
    private fun initialStateFromPrefs(): ProxyUiState {
        return try {
            val prefs = getApplication<Application>()
                .getSharedPreferences(ProxyService.PREFS, Context.MODE_PRIVATE)
            val running = prefs.getBoolean(ProxyService.KEY_RUNNING, false)
            val secret = prefs.getString(ProxyService.KEY_SECRET, "") ?: ""
            val cfDomain = prefs.getString(ProxyService.KEY_CF_DOMAIN, "") ?: ""
            val cfWorkerDomain = prefs.getString(ProxyService.KEY_CF_WORKER_DOMAIN, "") ?: ""
            val fakeTlsDomain = prefs.getString(ProxyService.KEY_FAKE_TLS_DOMAIN, "") ?: ""
            val host = ProxyService.DEFAULT_HOST
            val port = ProxyService.DEFAULT_PORT
            ProxyUiState(
                isLoading = false,
                isRunning = running,
                host = host,
                port = port,
                secret = secret,
                cfDomain = cfDomain,
                cfWorkerDomain = cfWorkerDomain,
                fakeTlsDomain = fakeTlsDomain,
                proxyLink = if (secret.isNotEmpty()) {
                    ProxyService.buildProxyLink(host, port, secret, fakeTlsDomain)
                } else {
                    ""
                }
            )
        } catch (_: Exception) {
            ProxyUiState(isLoading = false)
        }
    }

    private var proxyService: ProxyService? = null
    private var serviceBound = false
    private var collectJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ProxyService.ProxyBinder
            proxyService = binder.getService()
            serviceBound = true
            collectServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            serviceBound = false
            collectJob?.cancel()
            collectJob = null
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isRunning = false,
                connectionCount = 0
            )
        }
    }

    init {
        bindService()
    }

    private fun bindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, ProxyService::class.java)
        // bindService can decline (return false) instead of throwing when the service cannot be
        // created — without a check the UI would sit in its seeded state forever with no signal.
        val failure: String? = try {
            if (context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                null
            } else {
                "service unavailable"
            }
        } catch (error: RuntimeException) {
            error.localizedMessage ?: error.javaClass.simpleName
        }
        if (failure != null) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = context.getString(R.string.proxy_error_start_failed, failure)
            )
        }
    }

    private fun collectServiceState() {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            proxyService?.serviceState?.collect { serviceState ->
                _uiState.value = ProxyUiState(
                    isLoading = false,
                    isRunning = serviceState.isRunning,
                    host = serviceState.host,
                    port = serviceState.port,
                    secret = serviceState.secret,
                    connectionCount = serviceState.connectionCount,
                    logs = serviceState.logs,
                    proxyLink = serviceState.proxyLink,
                    cfDomain = serviceState.cfDomain,
                    cfWorkerDomain = serviceState.cfWorkerDomain,
                    fakeTlsDomain = serviceState.fakeTlsDomain,
                    bytesUp = serviceState.bytesUp,
                    bytesDown = serviceState.bytesDown,
                    startedAt = serviceState.startedAt,
                    route = serviceState.route,
                    // This rebuilds from scratch rather than copy()ing, so anything the service owns
                    // has to be mapped here or it is silently dropped on the next emission — and the
                    // service is exactly who knows why a start failed.
                    error = serviceState.error,
                    // The filter is UI-owned, not service-owned: carry it over explicitly or every
                    // service emission would snap the chips back to All.
                    logFilter = _uiState.value.logFilter
                )
            }
        }
    }

    fun toggleProxy() {
        val context = getApplication<Application>()
        val current = _uiState.value
        if (current.isLoading) return
        // Drop the previous failure the moment the button is pressed rather than waiting for the
        // service to re-emit: the reason belongs to the attempt that just ended, and leaving it
        // under the spinner reads as if the new attempt had already failed too.
        _uiState.value = current.copy(isLoading = true, error = null)

        try {
            if (current.isRunning) {
                val intent = Intent(context, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_STOP
                }
                context.startService(intent)
            } else {
                val intent = Intent(context, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_START
                }
                context.startForegroundService(intent)
            }
        } catch (error: RuntimeException) {
            // A Compose click must never crash the app: startForegroundService throws
            // ForegroundServiceStartNotAllowedException on Android 12+ (or SecurityException where
            // the FGS permission is missing). Surface it through the same error slot the service
            // uses, which the dial already renders under the state label.
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = context.getString(
                    R.string.proxy_error_start_failed,
                    error.localizedMessage ?: error.javaClass.simpleName
                )
            )
        }
    }

    /** Rotate the proxy secret (only effective while the proxy is stopped). */
    fun regenerateSecret() {
        val service = proxyService
        if (service != null) {
            service.regenerateSecret()
            return
        }
        // The service bind is async, so a tap can land before onServiceConnected — the call used
        // to be dropped silently. Write the same prefs the service itself writes; they are the
        // source of truth it reads on the next start.
        if (_uiState.value.isRunning) return
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val secret = bytes.joinToString("") { "%02x".format(it) }
        prefs().edit().putString(ProxyService.KEY_SECRET, secret).apply()
        val state = _uiState.value
        _uiState.value = state.copy(
            secret = secret,
            proxyLink = ProxyService.buildProxyLink(state.host, state.port, secret, state.fakeTlsDomain),
            // Rotating the key retires a pending "bad secret" failure the same way the service does.
            error = null
        )
    }

    fun setCfDomain(domain: String) {
        // Optimistically reflect in the UI even before the service flow emits.
        _uiState.value = _uiState.value.copy(cfDomain = domain)
        val service = proxyService
        if (service != null) {
            service.setCfDomain(domain)
        } else {
            // Same async-bind gap as regenerateSecret(): persist directly so the write is not lost.
            prefs().edit().putString(ProxyService.KEY_CF_DOMAIN, domain.trim()).apply()
        }
    }

    fun setCfWorkerDomain(domain: String) {
        _uiState.value = _uiState.value.copy(cfWorkerDomain = domain)
        val service = proxyService
        if (service != null) {
            service.setCfWorkerDomain(domain)
        } else {
            prefs().edit().putString(ProxyService.KEY_CF_WORKER_DOMAIN, domain.trim()).apply()
        }
    }

    fun setFakeTlsDomain(domain: String) {
        _uiState.value = _uiState.value.copy(fakeTlsDomain = domain)
        val service = proxyService
        if (service != null) {
            service.setFakeTlsDomain(domain)
        } else {
            prefs().edit().putString(ProxyService.KEY_FAKE_TLS_DOMAIN, domain.trim()).apply()
        }
    }

    fun setLogFilter(filter: LogFilter) {
        _uiState.value = _uiState.value.copy(logFilter = filter)
    }

    private fun prefs() =
        getApplication<Application>().getSharedPreferences(ProxyService.PREFS, Context.MODE_PRIVATE)

    override fun onCleared() {
        super.onCleared()
        collectJob?.cancel()
        if (serviceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

data class ProxyUiState(
    val isLoading: Boolean = true,
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
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val startedAt: Long = 0,
    val route: String = "",
    /** Localized, actionable reason the last start attempt failed; null = nothing to show. */
    val error: String? = null,
    /** Which log lines the log section renders; UI-owned, survives service state rebuilds. */
    val logFilter: LogFilter = LogFilter.ALL
)

/** Log scopes offered above the log list. */
enum class LogFilter { ALL, CONNECTIONS, PROBLEMS }

private val CONNECTION_KINDS = setOf(
    LogKind.CONN, LogKind.HANDSHAKE, LogKind.FAKE_TLS, LogKind.CLOUDFLARE, LogKind.WS, LogKind.PLAIN
)

/**
 * The log the UI actually shows. DEBUG lines never pass any scope on purpose: they are written for
 * a future verbose mode, and a toggle now would just bury the signal the filters exist to surface.
 */
fun List<LogLine>.visibleLogs(scope: LogFilter): List<LogLine> = filter { line ->
    line.kind != LogKind.DEBUG && when (scope) {
        LogFilter.ALL -> true
        LogFilter.CONNECTIONS -> line.kind in CONNECTION_KINDS
        LogFilter.PROBLEMS -> line.kind == LogKind.ERROR || line.kind == LogKind.WARNING
    }
}
