package com.tgwsproxy.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.ComponentName
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.tgwsproxy.MainActivity
import com.tgwsproxy.R
import com.tgwsproxy.core.ByeDpiProxy
import com.tgwsproxy.service.DesyncTileService
import com.tgwsproxy.desync.DesyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * VPN that captures the target apps' traffic and applies the DPI-desync to each new TLS
 * connection — the engine behind "разблокировать YouTube / Instagram".
 *
 * Flow: TUN → read IPv4 packets → TCP goes through [TcpConnection] (desync on the ClientHello),
 * UDP through [UdpAssociation] (QUIC dropped when the toggle is on so apps fall back to TLS).
 * IPv6 is captured and dropped to force apps onto IPv4 where the desync works.
 */
class DesyncVpnService : VpnService(), Tunnel {

    data class VpnState(
        val isRunning: Boolean = false,
        val preset: String = PRESET_TLSREC,
        val blockQuic: Boolean = true,
        val scopeAllApps: Boolean = true,
        val activeTcp: Int = 0,
        val activeUdp: Int = 0,
        val bytesUp: Long = 0,
        val bytesDown: Long = 0,
        val connOk: Int = 0,
        val connFail: Int = 0,
        val startedAt: Long = 0,
        val error: String? = null,
    )

    companion object {
        const val ACTION_START = "com.tgwsproxy.vpn.START"
        const val ACTION_STOP = "com.tgwsproxy.vpn.STOP"

        const val PREFS = "tgwsproxy_prefs"
        const val KEY_PRESET = "desync_preset"
        const val KEY_BLOCK_QUIC = "desync_block_quic"
        const val KEY_ALL_APPS = "desync_all_apps"
        const val KEY_VPN_RUNNING = "desync_vpn_running"
        // Custom byedpi command line (empty → derived from the selected preset).
        const val KEY_BYEDPI_CMD = "byedpi_cmd"
        // User-chosen packages to keep OFF the bypass (StringSet), on top of EXCLUDED_APPS.
        const val KEY_EXCLUDED_USER = "desync_excluded_user"

        const val PRESET_TLSREC = "tlsrec"
        const val PRESET_SPLIT = "split"
        const val PRESET_AUTO = "auto"
        const val PRESET_OFF = "off"

        // Local byedpi SOCKS5 endpoint that the userspace TCP relay dials.
        private const val SOCKS_PORT = 1080

        /**
         * byedpi desync arguments per preset (the real DPI bypass). Tuned for YouTube + Instagram
         * on RU providers; all three carry `-a1` (auto-retry) and apply to every captured flow.
         * A non-empty custom command in prefs overrides these.
         *
         *   Авто (AUTO)   : cascading disorder+split across many offsets — the strongest general
         *                  strategy, confirmed to unblock YouTube + Instagram on RU TSPU.
         *   Метод A (TLSREC): split + tlsrec + a low-TTL FAKE decoy (`-f-1 -t8`) — use when the plain
         *                  cascade isn't enough and the operator needs a poisoning packet.
         *   Метод B (SPLIT) : a pure multi-point SNI split (no disorder) — lighter / lower-latency
         *                  alternative for operators where disorder breaks the flow.
         * Flags: -d disorder, -s split, -r tlsrec, -f fake, -t fake TTL, +s = cut at the SNI.
         */
        fun presetToByedpiArgs(preset: String): String = when (preset) {
            PRESET_AUTO -> "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1"
            PRESET_TLSREC -> "-d1 -s1+s -r1+s -f-1 -t8 -a1"
            PRESET_SPLIT -> "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1"
            PRESET_OFF -> "" // plain SOCKS relay, no desync
            else -> "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1"
        }

        /**
         * Tokenise a byedpi command line into an argv array (argv[0] = "ciadpi").
         * We always pin the listen endpoint to 127.0.0.1:<port>; the rest is the user/preset
         * strategy — community/BBD command strings paste in verbatim (engine = byedpi v0.17.3).
         */
        fun buildByedpiArgs(command: String, ip: String, port: Int): Array<String> {
            val base = mutableListOf("ciadpi", "-i", ip, "-p", port.toString())
            base.addAll(filterListenFlags(shellSplit(command)))
            return base.toTypedArray()
        }

        /**
         * Strip any listen-endpoint flags (-i/--ip, -p/--port) from a user/preset command so it
         * can never override the pinned 127.0.0.1:<port>. Without this, a pasted "-i 0.0.0.0"
         * would (getopt last-wins) expose the auth-less SOCKS5 proxy to the whole network.
         * Handles both "-i 0.0.0.0" and the glued "-i0.0.0.0" / "--ip=0.0.0.0" forms.
         *
         * The glued short form is matched narrowly — only when the char right after -i/-p is the
         * start of an address/port value (digit, '.' or ':'), e.g. "-i0.0.0.0", "-p1080", "-i::".
         * This deliberately does NOT swallow unrelated tokens that merely begin with -i/-p (a
         * future "-probe" or "-ipv6"), which a blanket startsWith would wrongly drop.
         */
        private fun filterListenFlags(tokens: List<String>): List<String> {
            val out = ArrayList<String>(tokens.size)
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                val separate = t == "-i" || t == "-p" || t == "--ip" || t == "--port"
                val glued = (t.startsWith("-i") || t.startsWith("-p")) && t.length > 2 &&
                    (t[2].isDigit() || t[2] == '.' || t[2] == ':')
                val longGlued = t.startsWith("--ip=") || t.startsWith("--port=")
                if (separate || glued || longGlued) {
                    // separate-argument form ("-i" "0.0.0.0") — also drop the following value
                    if (separate && i + 1 < tokens.size) i++
                    i++
                    continue
                }
                out.add(t)
                i++
            }
            return out
        }

        private fun shellSplit(s: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var quote = 0.toChar()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    quote != 0.toChar() -> {
                        if (c == quote) quote = 0.toChar() else sb.append(c)
                    }
                    c == '\'' || c == '"' -> quote = c
                    c.isWhitespace() -> { if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) } }
                    else -> sb.append(c)
                }
                i++
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
            return out
        }

        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "desync_vpn_channel"

        private const val TUN_ADDR = "10.111.222.1"
        private const val MTU = 1500
        private const val UDP_IDLE_MS = 30_000L

        // Beta allowlist — the apps we actually want to unblock.
        val TARGET_APPS = listOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.instagram.android",
        )

        // Apps that DETECT the desync/VPN and block login — keep them off the bypass entirely
        // (only relevant in all-apps mode; in per-app mode they're not routed anyway).
        val EXCLUDED_APPS = listOf(
            "ru.sberbankmobile",            // СберБанк Онлайн
            "com.idamob.tinkoff.android",  // Т-Банк (Тинькофф)
            "ru.rostel",                   // Госуслуги
            "ru.ozon.app.android",         // Ozon
            "com.wildberries.ru",          // Wildberries
            "ru.tander.magnit",            // Магнит
            "ru.pyaterochka.app.browser",  // Пятёрочка
            "ru.pyaterochka.app",          // Пятёрочка (старый пакет)
            "ru.perekrestok.app",          // Перекрёсток
        )

        private val _state = MutableStateFlow(VpnState())
        val state: StateFlow<VpnState> = _state.asStateFlow()
    }

    private var pfd: ParcelFileDescriptor? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private val tunWriteLock = Any()

    private val tcpMap = ConcurrentHashMap<Long, TcpConnection>()
    private val udpMap = ConcurrentHashMap<Long, UdpAssociation>()

    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val connOk = AtomicLong(0)
    private val connFail = AtomicLong(0)

    private var method: DesyncEngine.Method? = DesyncEngine.Method.TLSREC
    private var blockQuic = true
    private var allApps = true
    private var excludedUser: Set<String> = emptySet()
    private var byedpiArgs: Array<String> = arrayOf("ciadpi")

    private var byedpiProxy: ByeDpiProxy? = null
    private var byedpiThread: Thread? = null
    @Volatile private var byedpiExitCode: Int? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readThread: Thread? = null
    private var statsJob: Job? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun loadPrefs() {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        blockQuic = p.getBoolean(KEY_BLOCK_QUIC, true)
        allApps = p.getBoolean(KEY_ALL_APPS, true)
        excludedUser = p.getStringSet(KEY_EXCLUDED_USER, emptySet())?.toSet() ?: emptySet()
        val preset = p.getString(KEY_PRESET, PRESET_AUTO) ?: PRESET_AUTO
        method = when (preset) {
            PRESET_SPLIT -> DesyncEngine.Method.SPLIT
            PRESET_AUTO, PRESET_TLSREC -> DesyncEngine.Method.TLSREC
            PRESET_OFF -> null
            else -> DesyncEngine.Method.TLSREC
        }
        // Custom command wins; otherwise derive byedpi args from the preset.
        val custom = (p.getString(KEY_BYEDPI_CMD, "") ?: "").trim()
        val command = if (custom.isNotEmpty()) custom else presetToByedpiArgs(preset)
        byedpiArgs = buildByedpiArgs(command, "127.0.0.1", SOCKS_PORT)
    }

    /**
     * Start the native byedpi engine as a local SOCKS5 proxy on a background thread.
     * byedpi's main() blocks while serving, so we launch it and briefly wait: if it returns
     * (thread dies) with a non-zero code right away, the command was invalid → fail.
     * Returns true if the proxy is up.
     */
    private fun startByedpi(): Boolean {
        return try {
            val proxy = ByeDpiProxy()
            byedpiProxy = proxy
            byedpiExitCode = null
            byedpiThread = thread(name = "byedpi-loop", isDaemon = true) {
                try { byedpiExitCode = proxy.startProxy(byedpiArgs) }
                catch (e: Throwable) { byedpiExitCode = -1 }
            }
            // Give byedpi a moment to bind/parse; if it already exited, the command was bad.
            Thread.sleep(500)
            val t = byedpiThread
            if (t == null || !t.isAlive) {
                val code = byedpiExitCode ?: -1
                _state.value = VpnState(isRunning = false, error = "byedpi: неверная команда (код $code)")
                return false
            }
            true
        } catch (e: Throwable) {
            _state.value = VpnState(isRunning = false, error = "byedpi не запустился: ${e.message}")
            false
        }
    }

    private fun stopByedpi() {
        val proxy = byedpiProxy
        try { proxy?.stopProxy() } catch (_: Throwable) {}
        // Give the loop up to 2s to unwind; if it's still alive, hard-close the socket.
        val t = byedpiThread
        try {
            t?.join(2000)
            if (t != null && t.isAlive) { try { proxy?.forceClose() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
        byedpiProxy = null
        byedpiThread = null
        byedpiExitCode = null
    }

    private fun startVpn() {
        if (running) return
        loadPrefs()
        createChannel()
        startForegroundCompat()

        // Bring up the native byedpi SOCKS5 proxy first — the relay dials it for every TCP flow.
        if (!startByedpi()) { stopForegroundCompat(); stopSelf(); return }

        // IPv4 only on purpose: we do NOT add an IPv6 address/route. If we advertised IPv6 on the
        // TUN, apps (YouTube/Instagram use Happy Eyeballs) would prefer AAAA/IPv6 and we'd have to
        // silently drop those packets → multi-second connect stalls instead of an instant IPv4
        // path. With no IPv6 on the interface, apps go straight to IPv4 where the desync applies.
        val builder = Builder()
            .setSession("Jevio Unblocker")
            .setMtu(MTU)
            .addAddress(TUN_ADDR, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")

        if (allApps) {
            // Our own app must bypass the TUN (byedpi's upstream socket reaches the net directly).
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            // Banking / gov / marketplace apps detect the desync and block login — keep them OFF
            // the bypass (built-in list + whatever the user picked) so they keep working normally.
            for (pkg in (EXCLUDED_APPS + excludedUser).toHashSet()) {
                try { builder.addDisallowedApplication(pkg) } catch (_: Exception) { /* not installed */ }
            }
        } else {
            var added = 0
            for (pkg in TARGET_APPS) {
                try { builder.addAllowedApplication(pkg); added++ } catch (_: Exception) { /* not installed */ }
            }
            // If none of the target apps are installed, fall back to routing self-excluded all-apps
            // so the user at least sees it work in a browser.
            if (added == 0) {
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
        }

        try {
            val fd = builder.establish() ?: throw IllegalStateException("establish() returned null")
            pfd = fd
            tunIn = FileInputStream(fd.fileDescriptor)
            tunOut = FileOutputStream(fd.fileDescriptor)
        } catch (e: Exception) {
            _state.value = VpnState(isRunning = false, error = "Не удалось поднять VPN: ${e.message}")
            stopByedpi()
            stopSelf()
            return
        }

        running = true
        persistRunning(true)
        bytesUp.set(0); bytesDown.set(0); connOk.set(0); connFail.set(0)
        _state.value = VpnState(
            isRunning = true,
            preset = presetString(),
            blockQuic = blockQuic,
            scopeAllApps = allApps,
            startedAt = System.currentTimeMillis(),
        )

        readThread = thread(name = "tun-read", isDaemon = true) { readLoop() }
        startStats()
    }

    private fun readLoop() {
        val input = tunIn ?: return
        val buffer = ByteArray(MTU + 80)
        try {
            while (running) {
                val n = input.read(buffer)
                if (n <= 0) { if (n < 0) break else continue }
                val packet = buffer.copyOf(n)
                if (PacketUtils.ipVersion(packet) != 4) continue // drop IPv6 → force IPv4
                // Drop malformed/truncated packets before the L4 accessors index by ihl/dataOffset —
                // a crafted short packet would otherwise throw and tear down the whole VPN (DoS).
                if (!PacketUtils.isWellFormedIpv4L4(packet)) continue
                when (PacketUtils.protocol(packet)) {
                    PacketUtils.PROTO_TCP -> handleTcp(packet)
                    PacketUtils.PROTO_UDP -> handleUdp(packet)
                }
            }
        } catch (_: Exception) {
            // TUN closed or read error → stop
        } finally {
            if (running) stopEverything()
        }
    }

    private fun handleTcp(packet: ByteArray) {
        val srcPort = PacketUtils.srcPort(packet)
        val dstIpInt = PacketUtils.dstIpInt(packet)
        val dstPort = PacketUtils.dstPort(packet)
        val key = PacketUtils.flowKey(srcPort, dstIpInt, dstPort)
        val flags = PacketUtils.tcpFlags(packet)
        val seq = PacketUtils.tcpSeq(packet)
        val ack = PacketUtils.tcpAck(packet)
        val win = PacketUtils.tcpWindow(packet)
        val payload = PacketUtils.tcpPayload(packet)
        // Count the whole IP packet (matches bytesDown in writeToTun) so the UI stats are symmetric.
        bytesUp.addAndGet(packet.size.toLong())

        val existing = tcpMap[key]
        if (existing != null) {
            existing.onPacket(seq, ack, flags, win, payload)
            return
        }
        val isSyn = flags and PacketUtils.TcpFlag.SYN != 0 && flags and PacketUtils.TcpFlag.ACK == 0
        if (isSyn) {
            val conn = TcpConnection(
                clientIp = PacketUtils.srcIp(packet), clientPort = srcPort,
                serverIp = PacketUtils.dstIp(packet), serverPort = dstPort,
                tunnel = this, key = key, socksPort = SOCKS_PORT
            )
            tcpMap[key] = conn
            conn.onSyn(seq, win)
        }
        // Non-SYN with no connection → stale; ignore (client will time out / RST).
    }

    private fun handleUdp(packet: ByteArray) {
        val dstPort = PacketUtils.dstPort(packet)
        // QUIC = UDP/443. Drop it so the app retries over TCP/TLS, which we can desync.
        if (blockQuic && dstPort == 443) return

        val srcPort = PacketUtils.srcPort(packet)
        val dstIpInt = PacketUtils.dstIpInt(packet)
        // UDP and TCP live in separate maps, so the raw flow key is enough here.
        val key = PacketUtils.flowKey(srcPort, dstIpInt, dstPort)
        val payload = PacketUtils.udpPayload(packet)
        if (payload.isEmpty()) return
        bytesUp.addAndGet(packet.size.toLong())

        var assoc = udpMap[key]
        if (assoc == null) {
            assoc = UdpAssociation(
                clientIp = PacketUtils.srcIp(packet), clientPort = srcPort,
                serverIp = PacketUtils.dstIp(packet), serverPort = dstPort,
                tunnel = this, key = key
            )
            udpMap[key] = assoc
            if (!assoc.start()) { udpMap.remove(key); return }
        }
        assoc.onClientPayload(payload)
    }

    // ---- Tunnel ----

    override fun writeToTun(packet: ByteArray) {
        synchronized(tunWriteLock) {
            try {
                // FileOutputStream is unbuffered so flush() is a no-op today, but it keeps us
                // correct if this is ever wrapped in a BufferedOutputStream.
                tunOut?.write(packet)
                tunOut?.flush()
                bytesDown.addAndGet(packet.size.toLong())
            } catch (_: Exception) {}
        }
    }

    override fun protectTcp(socket: Socket): Boolean =
        try { protect(socket) } catch (_: Exception) { false }

    override fun protectUdp(socket: DatagramSocket): Boolean =
        try { protect(socket) } catch (_: Exception) { false }

    override fun onConnectionClosed(key: Long, udp: Boolean) {
        if (udp) udpMap.remove(key) else tcpMap.remove(key)
    }

    override fun reportError(msg: String) {
        // Keep only the most recent reason; the stats loop preserves it via copy().
        _state.value = _state.value.copy(error = msg)
    }

    override fun onConnectResult(success: Boolean) {
        if (success) connOk.incrementAndGet() else connFail.incrementAndGet()
    }

    // ---- stats / lifecycle ----

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && running) {
                reapIdleUdp()
                _state.value = _state.value.copy(
                    activeTcp = tcpMap.size,
                    activeUdp = udpMap.size,
                    bytesUp = bytesUp.get(),
                    bytesDown = bytesDown.get(),
                    connOk = connOk.get().toInt(),
                    connFail = connFail.get().toInt(),
                )
                delay(1000)
            }
        }
    }

    private fun reapIdleUdp() {
        val now = System.currentTimeMillis()
        for ((k, a) in udpMap) {
            if (now - a.lastUsed > UDP_IDLE_MS) { a.close(); udpMap.remove(k) }
        }
    }

    private fun stopEverything() {
        running = false
        statsJob?.cancel()
        for (c in tcpMap.values) c.close()
        for (u in udpMap.values) u.close()
        tcpMap.clear(); udpMap.clear()
        stopByedpi()
        try { tunIn?.close() } catch (_: Exception) {}
        try { tunOut?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        persistRunning(false)
        _state.value = VpnState(isRunning = false)
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        scope.cancel()
        persistRunning(false)
        _state.value = VpnState(isRunning = false)
    }

    override fun onRevoke() {
        // User turned us off in system VPN settings.
        stopEverything()
        super.onRevoke()
    }

    private fun persistRunning(on: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VPN_RUNNING, on).apply()
        // Nudge the Quick Settings tile to re-read real state, so it reflects truth after the
        // fact instead of optimistically guessing on click (matches ProxyService behaviour).
        try {
            TileService.requestListeningState(this, ComponentName(this, DesyncTileService::class.java))
        } catch (_: Exception) {}
    }

    private fun presetString(): String = when (method) {
        DesyncEngine.Method.SPLIT -> PRESET_SPLIT
        DesyncEngine.Method.TLSREC -> PRESET_TLSREC
        null -> PRESET_OFF
        else -> PRESET_TLSREC
    }

    // ---- notification ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Разблокировка", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Статус обхода блокировок YouTube / Instagram"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DesyncVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Разблокировка включена")
            .setContentText("YouTube и Instagram обходят блокировку")
            .setSmallIcon(R.drawable.ic_tile_shield)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Отключить", stop)
            .build()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
