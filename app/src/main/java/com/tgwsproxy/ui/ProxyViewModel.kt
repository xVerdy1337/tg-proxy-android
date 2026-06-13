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

    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

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
                    cfDomain = serviceState.cfDomain
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

    fun setCfDomain(domain: String) {
        // Optimistically reflect in the UI even before the service flow emits.
        _uiState.value = _uiState.value.copy(cfDomain = domain)
        proxyService?.setCfDomain(domain)
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
    val cfDomain: String = ""
)
