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
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
        /** True while byedpi + TUN are still coming up after the user pressed enable. */
        val isStarting: Boolean = false,
        /** True while sockets, native byedpi and the TUN interface are being closed. */
        val isStopping: Boolean = false,
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
        private const val DEFAULT_SOCKS_PORT = 1080
        private val SOCKS_PORT_CANDIDATES = intArrayOf(1080, 18080, 28080, 38080)

        /**
         * byedpi desync arguments are supplied by the shared catalog. A non-empty custom command
         * in prefs overrides the selected catalog preset.
         *
         *   Авто (AUTO)   : cascading disorder+split across many offsets — the strongest general
         *                  strategy, confirmed to unblock YouTube + Instagram on RU TSPU.
         *   Метод A (TLSREC): split + tlsrec + a low-TTL FAKE decoy (`-f-1 -t8`) — use when the plain
         *                  cascade isn't enough and the operator needs a poisoning packet.
         *   Метод B (SPLIT) : a pure multi-point SNI split (no disorder) — lighter / lower-latency
         *                  alternative for operators where disorder breaks the flow.
         * Flags: -d disorder, -s split, -r tlsrec, -f fake, -t fake TTL, +s = cut at the SNI.
         */
        fun presetToByedpiArgs(preset: String): String = ByedpiPresetCatalog.commandFor(preset)

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
         * Sanitise a user/preset byedpi command before it reaches the native engine. Two goals:
         *
         *  1. The listen endpoint (-i/--ip, -p/--port) must stay pinned to 127.0.0.1:<port>. A
         *     pasted "-i 0.0.0.0" would otherwise (getopt last-wins) expose the auth-less SOCKS5
         *     proxy to the whole network.
         *  2. A DPI-strategy string has no business touching the filesystem or daemonising the
         *     engine, so we also strip byedpi's file/daemon options: -y/--cache-file and
         *     -w/--pidfile (write arbitrary paths), -H/--hosts and -j/--ipset (read arbitrary
         *     files), and -D/--daemon, -E/--transparent.
         *
         * Handles the separate form ("-i" "0.0.0.0"), the long "=" form ("--ip=0.0.0.0"), and the
         * glued short form ("-i0.0.0.0", "-yfile"). For -i/-p the glued match stays narrow (value
         * must start with a digit/'.'/':'), so a future "-probe"/"-ipv6" isn't wrongly dropped;
         * the file flags glue-match any value.
         */
        private fun filterListenFlags(tokens: List<String>): List<String> {
            // Flags that take a following value (strip the flag AND its value).
            val valueShort = setOf("-i", "-p", "-y", "-w", "-H", "-j")
            val valueLong = setOf("--ip", "--port", "--cache-file", "--pidfile", "--hosts", "--ipset")
            // Bare boolean flags (no value) to strip: daemonize / transparent mode.
            val boolFlags = setOf("-D", "--daemon", "-E", "--transparent")
            // Short flags whose value may be glued directly to the flag.
            val gluableShort = listOf("-i", "-p", "-y", "-w", "-H", "-j")

            val out = ArrayList<String>(tokens.size)
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                if (t in boolFlags) { i++; continue }
                val separate = t in valueShort || t in valueLong
                val longGlued = valueLong.any { t.startsWith("$it=") }
                val glued = gluableShort.any { f ->
                    t.length > 2 && t.startsWith(f) && when (f) {
                        // -i/-p: only when the next char begins an address/port value, so we don't
                        // swallow an unrelated token that merely starts with -i/-p.
                        "-i", "-p" -> t[2].isDigit() || t[2] == '.' || t[2] == ':'
                        else -> true
                    }
                }
                if (separate || longGlued || glued) {
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
        // Idle TCP flows are reaped after this long with no client/server activity, so half-open
        // or abandoned flows can't accumulate threads/sockets forever (no TCP FIN/RST required).
        private const val TCP_IDLE_MS = 120_000L
        // Hard caps on concurrent flows. The relay pool size bounds live relay threads; the map
        // caps bound memory + reject new flows past the limit (defends against local flood → OOM).
        private const val MAX_RELAY_THREADS = 256
        private const val MAX_TCP_FLOWS = 1024
        private const val MAX_UDP_FLOWS = 512

        // Returned by relayExecutor once the pool is gone (after stopEverything) so a late
        // execute() is a clean no-op-then-reject instead of resurrecting a pool post-shutdown.
        private val REJECTING_EXECUTOR = Executor {
            throw java.util.concurrent.RejectedExecutionException("relay pool stopped")
        }

        // Beta allowlist — the apps we actually want to unblock.
        val TARGET_APPS = listOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.instagram.android",
            "com.facebook.katana",       // Facebook
            "com.instagram.barcelona",   // Threads
            "com.twitter.android",       // X (Twitter)
            "com.discord",               // Discord
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

    // The active preset name (PRESET_AUTO/TLSREC/SPLIT/OFF). Purely for reporting in
    // VpnState.preset — the real desync strategy is the byedpi command built in loadPrefs().
    // NOTE: byedpi (native ciadpi) is the data-path desync engine; the pure-Kotlin
    // DesyncEngine is only used by the direct-connection HelloProbe/StrategyTester, not here.
    private var activePreset: String = PRESET_AUTO
    private var blockQuic = true
    private var allApps = true
    private var excludedUser: Set<String> = emptySet()
    private var byedpiArgs: Array<String> = arrayOf("ciadpi")
    private var socksPort: Int = DEFAULT_SOCKS_PORT

    private var byedpiProxy: ByeDpiProxy? = null
    private var byedpiThread: Thread? = null
    @Volatile private var byedpiExitCode: Int? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readThread: Thread? = null
    private var statsJob: Job? = null
    @Volatile private var running = false
    @Volatile private var cleaningUp = false
    // Keep the startup failure visible after stopSelf() triggers onDestroy().
    @Volatile private var lastStopError: String? = null

    // Shared, BOUNDED pool for per-flow relay loops (see Tunnel.relayExecutor). Each TCP/UDP flow
    // occupies one thread for its lifetime (blocking read), so the pool size hard-caps concurrent
    // flows. SynchronousQueue + a fixed max means an excess flow is *rejected* (we drop it) rather
    // than spawning an unbounded number of threads — without the cap, a local app could open tens
    // of thousands of flows and OOM the process via pthread_create. Created in startVpn and torn
    // down in stopEverything; the getter never resurrects it after shutdown.
    @Volatile private var relayPool: ThreadPoolExecutor? = null
    override val relayExecutor: Executor
        get() = relayPool ?: REJECTING_EXECUTOR

    private fun newRelayPool(): ThreadPoolExecutor = ThreadPoolExecutor(
        4, MAX_RELAY_THREADS, 30L, TimeUnit.SECONDS, SynchronousQueue(),
        object : ThreadFactory {
            private val n = AtomicLong(0)
            override fun newThread(r: Runnable) =
                Thread(r, "vpn-relay-${n.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy() // excess flow → RejectedExecutionException (caller drops it)
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val current = _state.value
                if ((current.isRunning || current.isStarting) && !current.isStopping) {
                    _state.value = current.copy(isStarting = false, isStopping = true)
                }
                // Native shutdown can wait for relay threads. Keep it off the service main thread
                // so Compose can render the stopping state instead of freezing for a few seconds.
                scope.launch { stopEverything() }
                return START_NOT_STICKY
            }
            else -> {
                // Off the main thread so isStarting can paint immediately and byedpi's bind-wait
                // doesn't freeze the UI (or risk an ANR) for up to a few seconds.
                scope.launch {
                    try {
                        startVpn()
                    } catch (e: Exception) {
                        stopEverything(error = formatFailure("запуска VPN", e))
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun loadPrefs() {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        blockQuic = p.getBoolean(KEY_BLOCK_QUIC, true)
        allApps = p.getBoolean(KEY_ALL_APPS, true)
        excludedUser = p.getStringSet(KEY_EXCLUDED_USER, emptySet())?.toSet() ?: emptySet()
        val preset = p.getString(KEY_PRESET, PRESET_AUTO) ?: PRESET_AUTO
        activePreset = preset
        // Custom command wins; otherwise derive byedpi args from the preset.
        val custom = (p.getString(KEY_BYEDPI_CMD, "") ?: "").trim()
        val command = if (custom.isNotEmpty()) custom else presetToByedpiArgs(preset)
        socksPort = selectSocksPort()
        byedpiArgs = buildByedpiArgs(command, "127.0.0.1", socksPort)
    }

    private fun selectSocksPort(): Int {
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        for (candidate in SOCKS_PORT_CANDIDATES) {
            try {
                ServerSocket(candidate, 1, loopback).use {
                    return candidate
                }
            } catch (_: Exception) {
                // Another local proxy owns this port; try the next candidate.
            }
        }
        // All fixed candidates busy — pick any free ephemeral port instead of failing on 1080.
        try {
            ServerSocket(0, 1, loopback).use { return it.localPort }
        } catch (_: Exception) {
            return DEFAULT_SOCKS_PORT
        }
    }

    /**
     * Start the native byedpi engine as a local SOCKS5 proxy on a background thread.
     * byedpi's main() blocks while serving, so we launch it and briefly wait: if it returns
     * (thread dies) with a non-zero code right away, the command was invalid → fail.
     * Returns true if the proxy is up.
     */
    private fun startByedpi(): Boolean {
        // Drop any leftover instance (auto-tune, previous VPN session) so g_proxy_running is free.
        stopByedpi()
        return try {
            val proxy = ByeDpiProxy()
            byedpiProxy = proxy
            byedpiExitCode = null
            val args = byedpiArgs
            val port = socksPort
            byedpiThread = thread(name = "byedpi-loop", isDaemon = true) {
                try { byedpiExitCode = proxy.startProxy(args) }
                catch (_: Throwable) { byedpiExitCode = -1 }
            }
            // Readiness means the SOCKS listener accepts connections, not merely that its thread
            // has survived for an arbitrary delay.
            val deadline = System.currentTimeMillis() + 3_000
            var ready = false
            while (byedpiThread?.isAlive == true && System.currentTimeMillis() < deadline) {
                try {
                    Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 150) }
                    ready = true
                    break
                } catch (_: Exception) {
                    Thread.sleep(40)
                }
            }
            if (!ready) {
                val code = byedpiExitCode
                val alive = byedpiThread?.isAlive == true
                val reason = when {
                    !alive && code == -1 ->
                        "byedpi не запустился (порт $port занят или движок уже работает). Закрой ByeByeDPI/другие SOCKS и попробуй снова"
                    !alive && code != null ->
                        "byedpi завершился сразу (код $code). Проверь команду byedpi в настройках"
                    alive ->
                        "byedpi SOCKS не отвечает на 127.0.0.1:$port (таймаут). Попробуй ещё раз"
                    else ->
                        "byedpi SOCKS не готов на 127.0.0.1:$port"
                }
                _state.value = VpnState(isRunning = false, isStarting = false, error = reason)
                stopByedpi()
                return false
            }
            true
        } catch (e: Throwable) {
            _state.value = VpnState(
                isRunning = false,
                isStarting = false,
                error = "byedpi не запустился: ${e.message ?: e.javaClass.simpleName}",
            )
            stopByedpi()
            false
        }
    }

    private fun stopByedpi() {
        val proxy = byedpiProxy
        try { proxy?.stopProxy() } catch (_: Throwable) {}
        // Give the loop up to 2s to unwind; if it's still alive, hard-close the socket and wait again.
        val t = byedpiThread
        try {
            t?.join(2000)
            if (t != null && t.isAlive) {
                try { proxy?.forceClose() } catch (_: Throwable) {}
                try { t.join(1500) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        byedpiProxy = null
        byedpiThread = null
        byedpiExitCode = null
    }

    private fun startVpn() {
        // Atomically claim startup so double-tap / tile + UI can't race two byedpi instances.
        synchronized(this) {
            if (running || cleaningUp || _state.value.isStarting) return
            lastStopError = null
            loadPrefs()
            _state.value = VpnState(
                isRunning = false,
                isStarting = true,
                preset = activePreset,
                blockQuic = blockQuic,
                scopeAllApps = allApps,
                error = null,
            )
        }
        createChannel()
        startForegroundCompat()

        // Bring up the native byedpi SOCKS5 proxy first — the relay dials it for every TCP flow.
        if (!startByedpi()) {
            stopEverything(error = _state.value.error ?: "byedpi не запустился")
            return
        }
        // User may have hit stop while we waited for SOCKS readiness.
        if (!_state.value.isStarting || cleaningUp) return

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
            val reason = formatFailure("поднятия VPN", e)
            _state.value = VpnState(isRunning = false, isStarting = false, error = reason)
            stopEverything(error = reason)
            return
        }

        running = true
        relayPool = newRelayPool()
        persistRunning(true)
        bytesUp.set(0); bytesDown.set(0); connOk.set(0); connFail.set(0)
        _state.value = VpnState(
            isRunning = true,
            isStarting = false,
            preset = presetString(),
            blockQuic = blockQuic,
            scopeAllApps = allApps,
            startedAt = System.currentTimeMillis(),
            error = null,
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
            // Cap concurrent flows: drop the SYN if we're at the limit so a local flood can't
            // exhaust threads/memory. The client simply retries/times out — no resources spent.
            if (tcpMap.size >= MAX_TCP_FLOWS) return
            val conn = TcpConnection(
                clientIp = PacketUtils.srcIp(packet), clientPort = srcPort,
                serverIp = PacketUtils.dstIp(packet), serverPort = dstPort,
                tunnel = this, key = key, socksPort = socksPort
            )
            tcpMap[key] = conn
            // onSyn dispatches the upstream dial onto relayExecutor, which can reject when the pool
            // is full or stopped — undo the map insert so we don't leak a dead entry.
            try {
                conn.onSyn(seq, win)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                tcpMap.remove(key)
                conn.close()
            }
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
            // Cap concurrent UDP associations (same flood defense as TCP).
            if (udpMap.size >= MAX_UDP_FLOWS) return
            assoc = UdpAssociation(
                clientIp = PacketUtils.srcIp(packet), clientPort = srcPort,
                serverIp = PacketUtils.dstIp(packet), serverPort = dstPort,
                tunnel = this, key = key
            )
            udpMap[key] = assoc
            // start() dispatches a reader onto relayExecutor — can reject when full/stopped.
            val started = try { assoc.start() } catch (_: java.util.concurrent.RejectedExecutionException) { false }
            if (!started) { udpMap.remove(key); assoc.close(); return }
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
            var sinceReap = 0L
            while (isActive && running) {
                // Only the UI consumes these stats. When nobody is collecting _state (app closed),
                // poll far less often so we don't wake the CPU every second 24/7 while the VPN runs.
                val uiWatching = _state.subscriptionCount.value > 0
                val interval = if (uiWatching) 1000L else 10000L
                // Reap idle UDP + TCP at least every ~10s regardless of UI, so sockets/threads
                // from abandoned or half-open flows don't linger (and can't accumulate to OOM).
                sinceReap += interval
                if (uiWatching || sinceReap >= 10000L) { reapIdleFlows(); sinceReap = 0L }
                if (uiWatching) {
                    _state.value = _state.value.copy(
                        activeTcp = tcpMap.size,
                        activeUdp = udpMap.size,
                        bytesUp = bytesUp.get(),
                        bytesDown = bytesDown.get(),
                        connOk = connOk.get().toInt(),
                        connFail = connFail.get().toInt(),
                    )
                }
                delay(interval)
            }
        }
    }

    private fun reapIdleFlows() {
        val now = System.currentTimeMillis()
        for ((k, a) in udpMap) {
            if (now - a.lastUsed > UDP_IDLE_MS) { a.close(); udpMap.remove(k) }
        }
        // TCP flows have no FIN/RST guarantee (a half-open flow never closes itself), so reap any
        // that are closed or idle past TCP_IDLE_MS — close() removes the entry via onConnectionClosed.
        for ((k, c) in tcpMap) {
            if (c.isIdle(TCP_IDLE_MS)) { c.close(); tcpMap.remove(k) }
        }
    }

    private fun stopEverything(
        stopService: Boolean = true,
        error: String? = null,
        preserveExistingError: Boolean = false,
    ) {
        synchronized(this) {
            if (cleaningUp) return
            cleaningUp = true
        }
        try {
            if (preserveExistingError) {
                if (error != null) lastStopError = error
            } else {
                lastStopError = error
            }
            running = false
            statsJob?.cancel()
            for (c in tcpMap.values) c.close()
            for (u in udpMap.values) u.close()
            tcpMap.clear(); udpMap.clear()
            relayPool?.shutdownNow()
            relayPool = null
            stopByedpi()
            try { tunIn?.close() } catch (_: Exception) {}
            try { tunOut?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            tunIn = null; tunOut = null; pfd = null
            persistRunning(false)
            _state.value = VpnState(isRunning = false, isStarting = false, error = lastStopError)
            stopForegroundCompat()
            if (stopService) stopSelf()
        } finally {
            cleaningUp = false
        }
    }

    override fun onDestroy() {
        stopEverything(stopService = false, preserveExistingError = true)
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        // User turned us off in system VPN settings.
        stopEverything()
        super.onRevoke()
    }

    private fun formatFailure(stage: String, e: Exception): String =
        "Не удалось $stage: ${e.javaClass.simpleName}: ${e.message ?: "без подробностей"}"

    private fun persistRunning(on: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VPN_RUNNING, on).apply()
        // Nudge the Quick Settings tile to re-read real state, so it reflects truth after the
        // fact instead of optimistically guessing on click (matches ProxyService behaviour).
        try {
            TileService.requestListeningState(this, ComponentName(this, DesyncTileService::class.java))
        } catch (_: Exception) {}
    }

    private fun presetString(): String = activePreset

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
