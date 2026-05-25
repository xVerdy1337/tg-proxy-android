package com.tgwsproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
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
import java.security.SecureRandom

class ProxyService : Service() {

    companion object {
        const val ACTION_START = "com.tgwsproxy.action.START"
        const val ACTION_STOP = "com.tgwsproxy.action.STOP"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "proxy_channel"
    }

    data class ServiceState(
        val isRunning: Boolean = false,
        val host: String = "127.0.0.1",
        val port: Int = 1443,
        val secret: String = "",
        val connectionCount: Int = 0,
        val logs: List<String> = emptyList(),
        val proxyLink: String = ""
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

        val secret = generateSecret()
        val host = "127.0.0.1"
        val port = 1443
        val proxyLink = "tg://proxy?server=$host&port=$port&secret=dd$secret"

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

        startForeground(NOTIFICATION_ID, buildNotification())

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
                    }
                )
                proxyServer?.start()
            } catch (e: Exception) {
                addLog("Ошибка: ${e.message}")
                _serviceState.update { it.copy(isRunning = false) }
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun stopProxy() {
        serviceScope.launch {
            try {
                proxyServer?.stop()
                proxyServer = null
            } catch (_: Exception) {}
        }

        _serviceState.update {
            it.copy(
                isRunning = false,
                connectionCount = 0,
                logs = it.logs + "Прокси остановлен"
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun clearLogs() {
        _serviceState.update { it.copy(logs = emptyList()) }
    }

    private fun addLog(line: String) {
        _serviceState.update { state ->
            val newLogs = (state.logs + line).takeLast(200)
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.proxy_notification_title))
            .setContentText(getString(R.string.proxy_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
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
        serviceScope.cancel()
        proxyServer?.stop()
    }
}
