package com.tgwsproxy.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tgwsproxy.service.ProxyService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(initialStateFromPrefs())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

    /**
     * Seed the UI synchronously from persisted prefs so the very first frame already shows
     * the correct status. Without this, every cold open started with `isLoading = true` and
     * flashed the full-screen "connecting" spinner for the 1–2s it takes the service to bind
     * and emit — which looked like the proxy briefly dropping into a connecting state.
     * The real service state reconciles this within a moment once the binding completes.
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
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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
                    route = serviceState.route
                )
            }
        }
    }

    fun toggleProxy() {
        val context = getApplication<Application>()
        if (_uiState.value.isRunning) {
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
    }

    fun clearLogs() {
        proxyService?.clearLogs()
    }

    /** Rotate the proxy secret (only effective while the proxy is stopped). */
    fun regenerateSecret() {
        proxyService?.regenerateSecret()
    }

    fun setCfDomain(domain: String) {
        // Optimistically reflect in the UI even before the service flow emits.
        _uiState.value = _uiState.value.copy(cfDomain = domain)
        proxyService?.setCfDomain(domain)
    }

    fun setCfWorkerDomain(domain: String) {
        _uiState.value = _uiState.value.copy(cfWorkerDomain = domain)
        proxyService?.setCfWorkerDomain(domain)
    }

    fun setFakeTlsDomain(domain: String) {
        _uiState.value = _uiState.value.copy(fakeTlsDomain = domain)
        proxyService?.setFakeTlsDomain(domain)
    }

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
    val logs: List<String> = emptyList(),
    val proxyLink: String = "",
    val cfDomain: String = "",
    val cfWorkerDomain: String = "",
    val fakeTlsDomain: String = "",
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val startedAt: Long = 0,
    val route: String = ""
)
