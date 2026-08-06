package com.tgwsproxy.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
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

        /**
         * User-chosen packages to route THROUGH the bypass in "selected apps only" mode (StringSet).
         *
         * Absent ≠ empty, and the difference is the whole point: absent means the user has never
         * opened the picker, so [TARGET_APPS] is used as the starting point. An explicitly saved
         * empty set means they unchecked everything, which is honoured as written — see
         * [targetPackages]. Storing the default eagerly instead would have frozen whatever
         * TARGET_APPS looked like at install time, so a later build adding an app would never reach
         * anyone who had already run the old one.
         */
        const val KEY_TARGET_USER = "desync_target_user"

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
            // -U pins UDP off. We never speak SOCKS5 UDP ASSOCIATE to byedpi — VPN UDP goes out
            // through UdpAssociation's own protected DatagramSocket — so the engine's whole UDP
            // path is dead weight here, and it is the one part a pasted string can still abuse:
            // desync_udp() applies -O/--fake-offset to the fake packet without a lower bound, so a
            // negative offset walks pkt.data backwards out of its .bss buffer and sends whatever
            // it finds to the peer. Any co-resident app can drive that, since our listener is
            // auth-less on loopback. Unlike the -i pin this one cannot be beaten by a later token:
            // params.udp is only ever cleared (byedpi/main.c case 'U'), never set back.
            val base = mutableListOf("ciadpi", "-i", ip, "-p", port.toString(), "-U")
            base.addAll(filterListenFlags(shellSplit(command)))
            return base.toTypedArray()
        }

        /**
         * Sanitise a user/preset byedpi command before it reaches the native engine. Three goals:
         *
         *  1. The listen endpoint (-i/--ip, -p/--port) must stay pinned to 127.0.0.1:<port>. A
         *     pasted "-i 0.0.0.0" would otherwise (getopt last-wins) expose the auth-less SOCKS5
         *     proxy to the whole network.
         *  2. The egress must stay direct. -C/--connect-to makes byedpi forward *every* proxied
         *     connection to an arbitrary upstream host:port instead of the real destination, so a
         *     pasted "strategy" could silently route all of the user's traffic through a stranger's
         *     server — a wider hole than the -i hijack, and invisible in the UI.
         *  3. A DPI-strategy string has no business touching the filesystem or daemonising the
         *     engine, so we also strip byedpi's file/daemon options: -y/--cache-file and
         *     -w/--pidfile (write arbitrary paths), -P/--protect-path (a filesystem socket path —
         *     guarded by __linux__ upstream, so it *is* compiled in on Android), and -D/--daemon,
         *     -E/--transparent.
         *
         *     The read side is keyed on the PRIMITIVE, not on individual flags: every option whose
         *     value reaches ftob() (byedpi/main.c) fopen()s it and slurps the whole file unless it
         *     starts with ':'. There are exactly three such call sites — -H/--hosts, -j/--ipset and
         *     -l/--fake-data — and all three are blocked. -l is the dangerous one: the bytes are not
         *     merely read, they become the decoy payload that desync.c sends to the destination
         *     (whole-file for udp_fake), at a TTL the same command chooses via -t, so a high TTL
         *     turns "read a local file" into "ship it to a host of my choosing". Any future byedpi
         *     option that calls ftob() must be added here too.
         *  4. -B/--copy must go, for availability rather than confidentiality. It rewinds getopt by
         *     assigning to optind (byedpi/main.c), and only a later -A advances it again; without
         *     one, parse_args never terminates. "-B 1" rewinds to the initial group, whose _optind
         *     is 0, so argv is re-parsed from the start forever. That spins a thread we can no
         *     longer stop (server_fd is never published, so the flag stays raised) and every later
         *     start loses the singleton gate — one pasted string bricks the bypass until the user
         *     force-stops the app. No preset uses it.
         *
         * Matching has to mirror what getopt/byedpi actually accept, not what a well-formed command
         * looks like, because both of the obvious narrowings are bypassable:
         *
         *  - Glued short form. It is NOT enough to glue-match only values that look like an address
         *    ("-i0.0.0.0"): byedpi runs -i's value through get_addr_scheme() and get_addr()
         *    (byedpi/main.c), which strip a "socks5://"/"tcp://"/... scheme prefix and accept a
         *    bracketed literal, so "-i[::]" and "-isocks5://0.0.0.0" would sail past a
         *    digit/'.'/':' whitelist and still bind every interface. We therefore match on the flag
         *    letter alone. That is safe here: each blocked short spelling is a single letter, and
         *    no other byedpi short option is spelled with these letters.
         *  - Long form. getopt_long accepts any UNAMBIGUOUS abbreviation, so "--connect" reaches
         *    connect-to, "--pid" reaches pidfile and "--host" reaches hosts. We therefore match a
         *    long option by PREFIX. Over-matching an ambiguous abbreviation is harmless — byedpi
         *    rejects it anyway — and checked against the option table in byedpi/main.c, exactly one
         *    flag we want to keep is a prefix of a blocked name: --fake (-f) sits inside
         *    --fake-data (-l). getopt_long resolves an EXACT name match before any abbreviation, so
         *    --fake must still reach -f; [EXACT_KEEP_LONG] carries that one exception. Everything
         *    else (--proto, --cache-ttl, --cache-merge, --conn-ip, --comment, --pf, --help, --debug,
         *    --ttl, --tlsrec, --fake-sni, --fake-offset, --fake-tls-mod, …) is unaffected.
         *
         * Clustered shorts have to be decomposed, not prefix-matched. byedpi builds its getopt
         * optstring straight from options[] with no leading '+'/'-' (byedpi/main.c), so shorts
         * cluster freely and "-Ni 0.0.0.0" is -N followed by -i taking the NEXT argv. Inspecting
         * only position 1 would wave that through, and getopt's last-wins then beats our pin — the
         * same total bypass as a bare "-i", just wearing a harmless boolean in front. The same
         * shape reaches every other blocked flag ("-UC host:443", "-Xy /sdcard/x").
         */
        // Letters that take a value in byedpi's options[] table. The FIRST such letter in a cluster
        // swallows the rest of the token as its inline value, so scanning must stop there — in
        // "-nwww.ip.example" the "ip" is hostname bytes, not flags.
        // internal, not private: ByedpiPresetCatalog.migrateCommand walks clustered shorts the same
        // way and must use the same letter table — two copies of "which byedpi letters take a
        // value" would drift, and a wrong answer there silently changes what reaches the engine.
        internal const val VALUE_SHORT_LETTERS = "wipIbcxALuTByKHVRsdoqfntlOQeMrmagWPjC#/"

        // The long options with has_arg == 0 in byedpi's options[] table. Kept beside the short
        // letters for the same reason: anything walking a command has to know which tokens consume
        // the NEXT one, and assuming every long option does means a boolean silently masks the
        // token after it.
        internal val BOOLEAN_LONG_OPTIONS = setOf(
            "daemon", "no-domain", "no-ipv6", "no-udp", "help", "version",
            "transparent", "tfo", "md5sig", "wait-send", "drop-sack",
        )
        // The subset a user command may not carry, value-taking (i p y w H j C P l B) and boolean
        // (D E) alike. Case matters: -I (--conn-ip) is not -i, and -B (--copy) is not -b.
        private const val BLOCKED_SHORT_LETTERS = "ipywHjClBPDE"
        // getopt_long prefers an exact name over any abbreviation, so these must survive the
        // prefix match below even though they are prefixes of a blocked name. Currently only
        // --fake (-f), which sits inside --fake-data (-l).
        private val EXACT_KEEP_LONG = setOf("fake")

        private const val KEEP = 0
        private const val DROP = 1
        private const val DROP_WITH_VALUE = 2

        /**
         * Walk a clustered single-dash token the way getopt would and decide its fate. Returns
         * [DROP_WITH_VALUE] when the blocked letter ended the token, because getopt will then take
         * the following argv as its value and we must drop that too.
         */
        private fun classifyShortCluster(t: String): Int {
            for (idx in 1 until t.length) {
                val c = t[idx]
                val takesValue = c in VALUE_SHORT_LETTERS
                if (c in BLOCKED_SHORT_LETTERS) {
                    return if (takesValue && idx == t.length - 1) DROP_WITH_VALUE else DROP
                }
                // Reached a harmless flag that consumes the remainder as its value: everything
                // after it is data, so nothing dangerous can still be hiding in this token.
                if (takesValue) return KEEP
                // Otherwise it is a boolean (or a letter getopt will reject) — keep walking, the
                // dangerous one may sit further in.
            }
            return KEEP
        }

        private fun filterListenFlags(tokens: List<String>): List<String> {
            // Short flags that take a value (strip the flag AND its value, in both forms).
            val valueShort = listOf("-i", "-p", "-y", "-w", "-H", "-j", "-C", "-P", "-l", "-B")
            val boolShort = setOf("-D", "-E")
            // Long spellings, split by whether getopt consumes a following token for them.
            val valueLong = listOf(
                "--ip", "--port", "--cache-file", "--pidfile", "--hosts", "--ipset",
                "--connect-to", "--protect-path", "--fake-data", "--copy",
            )
            val boolLong = listOf("--daemon", "--transparent")

            val out = ArrayList<String>(tokens.size)
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                var drop = false
                var dropValue = false
                when {
                    t in boolShort -> drop = true
                    t in valueShort -> { drop = true; dropValue = true }
                    // Glued and/or clustered short form: "-i[::]", "-isocks5://0.0.0.0", "-p1080",
                    // "-yfile", "-Ni 0.0.0.0", "-UC host:443".
                    t.length > 2 && t.startsWith("-") && !t.startsWith("--") ->
                        when (classifyShortCluster(t)) {
                            DROP -> drop = true
                            DROP_WITH_VALUE -> { drop = true; dropValue = true }
                        }
                    t.length > 2 && t.startsWith("--") -> {
                        val name = t.substring(2).substringBefore('=')
                        when {
                            name.isEmpty() -> {}
                            name in EXACT_KEEP_LONG -> {}
                            valueLong.any { it.startsWith("--$name") } -> {
                                drop = true
                                // "--ip=0.0.0.0" carries its value inline; "--ip 0.0.0.0" does not.
                                dropValue = !t.contains('=')
                            }
                            boolLong.any { it.startsWith("--$name") } -> drop = true
                        }
                    }
                }
                if (drop) {
                    if (dropValue && i + 1 < tokens.size) i++
                    i++
                    continue
                }
                out.add(t)
                i++
            }
            return out
        }

        // internal for the same reason as VALUE_SHORT_LETTERS: anything that reasons about a
        // command's tokens must agree with the splitter the engine path actually uses, or it will
        // disagree exactly on quoted values.
        internal fun shellSplit(s: String): List<String> {
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

        /**
         * Block until byedpi's SOCKS5 listener on 127.0.0.1:[port] actually accepts a connection,
         * or [deadlineMs] elapses. Returns true only on a successful accept.
         *
         * The engine binds some way into its own start-up, so "the byedpi thread is still alive"
         * (or any fixed sleep) is not readiness: whoever dials first — the TUN relay here, the
         * StrategyTester there — would race the bind and see connection-refused on the very first
         * flow. Keeping this in one place means both callers agree on what "up" means.
         */
        fun awaitSocksReady(
            port: Int,
            deadlineMs: Long = 3_000L,
            alive: () -> Boolean = { true },
        ): Boolean {
            val deadline = System.currentTimeMillis() + deadlineMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 150) }
                    return true
                } catch (_: Exception) {
                    // byedpi's main() returns straight away on an invalid command or a lost bind
                    // race. Once the engine is gone no later attempt can succeed, so short-circuit
                    // instead of making the user watch out the whole deadline for a known failure.
                    if (!alive()) return false
                    try {
                        Thread.sleep(40)
                    } catch (_: InterruptedException) {
                        // Re-arm the flag we just consumed: the auto-tune sweep interrupts its
                        // worker to abort, and swallowing it here would strand that thread.
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }
            return false
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

        // Default-network switch handling (see registerNetworkCallback).
        //
        // The debounce window swallows the burst a single real switch produces — registration
        // replays onAvailable for the current network, and roaming between APs on the same SSID
        // fires repeatedly — so one walk from Wi-Fi to mobile costs one reset rather than several.
        // Matches ProxyService's 1.5s for the same reason it picked it: long enough to cover a
        // burst, short enough that two genuine switches in a row are still two resets.
        private const val NETWORK_DEBOUNCE_MS = 1_500L
        // And then let the new link finish coming up before inviting every app to redial onto it.
        // Same 700ms ProxyService settles for; a reset into a not-yet-usable network merely spends
        // the app's first retry, and some apps back off after it.
        private const val NETWORK_SETTLE_MS = 700L

        // Returned by relayExecutor once the pool is gone (after stopEverything) so a late
        // execute() is a clean no-op-then-reject instead of resurrecting a pool post-shutdown.
        private val REJECTING_EXECUTOR = Executor {
            throw java.util.concurrent.RejectedExecutionException("relay pool stopped")
        }

        // Default allowlist for "selected apps only" — the apps we actually want to unblock. Only a
        // STARTING POINT since the picker landed: the user's own set, once saved, replaces it
        // wholesale (see KEY_TARGET_USER and targetPackages).
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

        /**
         * The packages "selected apps only" mode should route, read from [prefs].
         *
         * Lives here rather than in either caller because the service (which builds the tunnel) and
         * the settings UI (which shows the count and the checkmarks) must agree exactly: a UI that
         * counted TARGET_APPS while the tunnel routed a saved set would show the user a list that
         * isn't the one in force. The absent-vs-empty distinction is described on [KEY_TARGET_USER].
         */
        fun targetPackages(prefs: android.content.SharedPreferences): Set<String> {
            val saved = prefs.getStringSet(KEY_TARGET_USER, null)
            return saved?.toSet() ?: TARGET_APPS.toSet()
        }

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
    private var targetUser: Set<String> = TARGET_APPS.toSet()
    private var byedpiArgs: Array<String> = arrayOf("ciadpi")
    private var socksPort: Int = DEFAULT_SOCKS_PORT

    // All three cross threads and all three feed stopByedpi()'s ownership test: the writer is the
    // startVpn coroutine, the readers are a different Dispatchers.IO worker (ACTION_STOP), the main
    // thread (onDestroy/onRevoke) and the tun-read thread. Nothing establishes happens-before
    // between them — startVpn's synchronized block ends before startByedpi() is even called — so a
    // stale read here means skipping the teardown of a live engine and then dropping its handles.
    @Volatile private var byedpiProxy: ByeDpiProxy? = null
    @Volatile private var byedpiThread: Thread? = null
    @Volatile private var byedpiExitCode: Int? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readThread: Thread? = null
    private var statsJob: Job? = null

    // Default-network watch. Registered once the TUN is up and dropped in stopEverything; the id and
    // timestamp are the debounce state (see onNetworkChanged) and are read from the callback thread
    // while the coroutine that acts on them runs on Dispatchers.IO, hence @Volatile.
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetworkId: String? = null
    @Volatile private var lastNetworkChangeAt = 0L
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
                // A null intent is the system redelivering START_STICKY after our process was
                // killed — not a user action. Without this gate any such restart re-established
                // the VPN even when the user had switched it off, so a tunnel could reappear
                // behind their back; consult the persisted flag instead (same reconciliation as
                // ProxyService) and stand down if we were not supposed to be running. Every real
                // start path (MainActivity, tile, BootReceiver) sets ACTION_START, so a
                // user-initiated start never reaches this branch with a null intent.
                if (intent == null) {
                    val shouldRun = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(KEY_VPN_RUNNING, false)
                    if (!shouldRun) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                // Off the main thread so isStarting can paint immediately and byedpi's bind-wait
                // doesn't freeze the UI (or risk an ANR) for up to a few seconds.
                scope.launch {
                    try {
                        startVpn()
                    } catch (e: Exception) {
                        stopEverything(error = formatFailure(getString(R.string.vpn_fail_start_stage), e))
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
        targetUser = targetPackages(p)
        val preset = p.getString(KEY_PRESET, PRESET_AUTO) ?: PRESET_AUTO
        activePreset = preset
        // Custom command wins; otherwise derive byedpi args from the preset.
        // migrateCommand, not the raw pref: a saved -A command written before the -T timeouts
        // existed has a dead auto-detect, and this path (tile, boot autostart) can launch it long
        // before the UI ever gets a chance to repair it.
        val custom = ByedpiPresetCatalog.migrateCommand((p.getString(KEY_BYEDPI_CMD, "") ?: "").trim())
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
     * byedpi's main() blocks while serving, so we launch it and then poll the listener for
     * readiness; a thread that dies before the port answers means the command was invalid or the
     * bind failed, and [awaitSocksReady] gives up as soon as that happens rather than at the
     * deadline. Returns true if the proxy is up.
     */
    private fun startByedpi(): Boolean {
        // No engine bundled for this ABI → say so instead of walking into the readiness poll, whose
        // vocabulary ("port busy", "SOCKS not ready") would blame a port conflict for a missing .so
        // and send the user looking for another proxy app to close.
        if (!ByeDpiProxy.nativeAvailable) {
            _state.value = VpnState(
                isRunning = false,
                isStarting = false,
                error = getString(R.string.byedpi_native_unavailable, ByeDpiProxy.primaryAbi()),
            )
            return false
        }
        // Drop a leftover run of OUR OWN from a previous VPN session, so the native singleton is
        // free before we claim it. This deliberately cannot reach an auto-tune instance: those live
        // on StrategyTester's own ByeDpiProxy and are not tracked by our handles — tearing one down
        // from here is what the ownership test in stopByedpi() exists to prevent.
        stopByedpi()
        return try {
            val proxy = ByeDpiProxy()
            byedpiProxy = proxy
            byedpiExitCode = null
            val args = byedpiArgs
            val port = socksPort
            // Publish the handle BEFORE starting the thread. kotlin.concurrent.thread() starts it
            // inside the call and only then assigns, which leaves a window where the engine is
            // already running while byedpiThread is still null — a stopByedpi() landing there would
            // see no owner, skip the teardown and clear the handles, orphaning a live listener that
            // nothing can ever stop again (the native gate then refuses every later start).
            val t = Thread({
                try { byedpiExitCode = proxy.startProxy(args) }
                catch (_: Throwable) { byedpiExitCode = -1 }
            }, "byedpi-loop")
            t.isDaemon = true
            byedpiThread = t
            t.start()
            // Readiness means the SOCKS listener accepts connections, not merely that its thread
            // has survived for an arbitrary delay. The liveness re-check matters because the port
            // could be answered by *someone else's* listener (ours lost the bind race after
            // selectSocksPort released it) — a dead engine thread means that accept wasn't us.
            val ready = awaitSocksReady(port) { byedpiThread?.isAlive == true } &&
                byedpiThread?.isAlive == true
            if (!ready) {
                val code = byedpiExitCode
                val alive = byedpiThread?.isAlive == true
                val reason = when {
                    !alive && code == -1 ->
                        getString(R.string.byedpi_port_busy, port)
                    !alive && code != null ->
                        getString(R.string.byedpi_exit_code, code)
                    alive ->
                        getString(R.string.byedpi_socks_timeout, port)
                    else ->
                        getString(R.string.byedpi_socks_not_ready, port)
                }
                _state.value = VpnState(isRunning = false, isStarting = false, error = reason)
                // stopByedpi() no-ops when this run no longer owns the engine — see its doc.
                stopByedpi()
                return false
            }
            true
        } catch (e: Throwable) {
            _state.value = VpnState(
                isRunning = false,
                isStarting = false,
                error = getString(R.string.byedpi_start_failed, e.message ?: e.javaClass.simpleName),
            )
            stopByedpi()
            false
        }
    }

    /**
     * Tear down *our* byedpi run, if we still have one.
     *
     * The ownership test has to live here rather than at the call sites: stopProxy/forceClose reach
     * the process-wide native globals, not [byedpiProxy], so they hit whichever run holds them
     * right now. A run whose startProxy already returned holds nothing — the native epilogue either
     * released the globals or found itself superseded — and firing anyway shuts down somebody
     * else's listener. Concretely: an auto-tune sweep owns the engine, the user enables the VPN from
     * the quick-settings tile, our start is refused, and we kill the listener the sweep is probing
     * through, so the strategy under test is scored as broken and may be dropped from the cache.
     * A null exit code means the thread is still inside main(), i.e. the run may be ours, so
     * unknown is treated as owned.
     */
    private fun stopByedpi() {
        val proxy = byedpiProxy
        val t = byedpiThread
        if (t != null && byedpiExitCode == null) {
            try { proxy?.stopProxy() } catch (_: Throwable) {}
            // Give the loop up to 2s to unwind; if it's still alive, hard-close the socket and wait
            // again.
            try {
                t.join(2000)
                if (t.isAlive) {
                    try { proxy?.forceClose() } catch (_: Throwable) {}
                    try { t.join(1500) } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        // Drop the handles either way. That is right for a run that has exited and for one we just
        // stopped; it is a deliberate write-off for the one case where both joins time out and the
        // thread is still alive, since we have no stronger lever than forceClose() and holding a
        // handle we can never act on only makes the next start refuse too.
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
            stopEverything(error = _state.value.error ?: getString(R.string.byedpi_start_failed_short))
            return
        }
        // User may have hit stop while we waited for SOCKS readiness. stopEverything() only gates on
        // cleaningUp, never on isStarting, so a stop can run to completion — including its own
        // stopByedpi() against still-null handles — while we are in here. Bailing out bare would
        // therefore strand the engine we just started: the service is gone, no further onDestroy
        // fires, and its auth-less SOCKS listener keeps serving with the native gate raised, so
        // every later start fails for the life of the process. Tear our own run down instead; the
        // ownership test in stopByedpi() is what makes calling it here safe.
        if (!_state.value.isStarting || cleaningUp) {
            stopByedpi()
            return
        }

        // IPv4 only on the *addresses*: we deliberately do not put an IPv6 address on the TUN. If we
        // advertised one, apps (YouTube/Instagram use Happy Eyeballs) would prefer AAAA/IPv6 and we'd
        // have to silently drop those packets → multi-second connect stalls instead of an instant
        // IPv4 path. With no IPv6 source address on the interface, apps go straight to IPv4 where
        // the desync applies.
        //
        // The IPv6 *route* is a different question, and it is why "::/0" is here. Claiming a route
        // for a family we hold no address for changes nothing about which family apps pick — that is
        // decided by source-address selection, and there is still no IPv6 source to select. What it
        // does decide is where a stray IPv6 packet goes when an app emits one anyway (a literal
        // AAAA destination, a socket bound before we came up, a library with its own resolver):
        // without the route it leaves on the underlying interface, outside the tunnel and outside
        // the desync, which is exactly the flow a DPI box is watching for. With it, the packet
        // arrives in the TUN and readLoop drops it — the app fails over to IPv4 in Happy Eyeballs
        // time instead of completing a plaintext handshake past the bypass.
        val builder = Builder()
            .setSession("Jevio Unblocker")
            .setMtu(MTU)
            .addAddress(TUN_ADDR, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
        // Guarded on its own: a device that refuses an IPv6 route it has no address for must fall
        // back to the previous behaviour (IPv6 unclaimed), not fail to establish the VPN at all.
        try { builder.addRoute("::", 0) } catch (_: Exception) {}

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
            for (pkg in targetUser) {
                try { builder.addAllowedApplication(pkg); added++ } catch (_: Exception) { /* not installed */ }
            }
            // Fall back to all-apps only when the allowlist could not be honoured at all — none of
            // the chosen packages is installed, so an empty allowlist would route NOTHING and the
            // VPN would look broken. Deliberately NOT reached when the user saved an empty set on
            // purpose: silently promoting "route nothing" to "route everything" would push traffic
            // through the desync that they had explicitly taken out of it, and in a build where
            // banking apps are excluded by name in the other branch only, that is the one direction
            // this fallback must never move.
            if (added == 0 && targetUser.isNotEmpty()) {
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
        }

        try {
            val fd = builder.establish() ?: throw IllegalStateException("establish() returned null")
            pfd = fd
            tunIn = FileInputStream(fd.fileDescriptor)
            tunOut = FileOutputStream(fd.fileDescriptor)
        } catch (e: Exception) {
            val reason = formatFailure(getString(R.string.vpn_fail_establish_stage), e)
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
        // After running = true, so the callback's own guard can't reject the registration replay for
        // a run that is in fact up. It only ever resets flows, and there are none yet.
        registerNetworkCallback()
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

    /**
     * Watch the device's default network so a Wi-Fi ↔ mobile switch doesn't leave the tunnel full of
     * flows that can never complete.
     *
     * Only the flows are reset — byedpi is deliberately left running. Its upstream sockets belong to
     * a process that is off the tunnel (self-excluded in all-apps mode, absent from the allow-list
     * otherwise), so every connect() it makes after the switch already goes out over the new default
     * network; the engine has nothing stale to rebuild. Restarting it would instead cost the SOCKS
     * bind-and-wait on every subway stop, and each restart is a chance for the port to be taken.
     *
     * Deliberately NOT paired with setUnderlyingNetworks(): a VPN that never sets it is defined to
     * follow the system default, which is exactly what we want. Pinning an explicit array would
     * replace that with a list we then have to keep correct by hand.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = cm
            val cb = object : ConnectivityManager.NetworkCallback() {
                // networkHandle is a stable per-network id (API 23+); Network.toString() is not
                // contractually stable and produced spurious "changed" events in ProxyService.
                override fun onAvailable(network: Network) {
                    onNetworkChanged(network.networkHandle.toString())
                }
            }
            networkCallback = cb
            cm.registerDefaultNetworkCallback(cb)
        } catch (_: Exception) {
            // Some OEMs throttle callback registrations. Losing the watch costs us the fast reset,
            // not the tunnel: flows still die off through reapIdleFlows and the app's own timeouts,
            // which is precisely the behaviour we had before this existed.
        }
    }

    private fun onNetworkChanged(id: String) {
        if (!running || cleaningUp) return
        val now = System.currentTimeMillis()
        // Registration fires onAvailable for the current network immediately, and roaming produces
        // bursts. Debounce both: the first call lands with lastNetworkId still null, which is
        // harmless because at that point there are no flows to reset.
        if (id == lastNetworkId && now - lastNetworkChangeAt < NETWORK_DEBOUNCE_MS) return
        lastNetworkId = id
        lastNetworkChangeAt = now
        scope.launch {
            // Let the new link finish coming up before we invite every app to redial onto it —
            // resetting into a network that cannot carry the connection yet just spends the app's
            // first retry, and some back off after it.
            delay(NETWORK_SETTLE_MS)
            if (!running || cleaningUp) return@launch
            resetFlows()
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
        lastNetworkId = null
        lastNetworkChangeAt = 0
    }

    /**
     * Drop every live flow so the apps behind them redial over the current network.
     *
     * TCP is reset rather than closed: the app is told the connection is gone instead of being left
     * to discover it when its own timeout expires. UDP has nothing to signal with — the association
     * is just closed, and the app's next datagram opens a fresh one.
     *
     * Iterating the ConcurrentHashMaps directly is safe here even though close() calls back into
     * onConnectionClosed to remove the entry: these views are weakly consistent, so a removal during
     * traversal is allowed. The explicit remove() after each close covers the one case the callback
     * cannot — an entry whose close() threw before reaching it.
     */
    private fun resetFlows() {
        for ((k, c) in tcpMap) {
            try { c.closeWithReset() } catch (_: Exception) {}
            tcpMap.remove(k)
        }
        for ((k, u) in udpMap) {
            try { u.close() } catch (_: Exception) {}
            udpMap.remove(k)
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
            // Before the maps are emptied: a switch landing mid-teardown must not queue a reset
            // against flows we are already closing.
            unregisterNetworkCallback()
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
        getString(
            R.string.vpn_fail_format,
            stage,
            e.javaClass.simpleName,
            e.message ?: getString(R.string.vpn_fail_no_details),
        )

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
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.desync_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            ch.description = getString(R.string.desync_channel_desc)
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
            .setContentTitle(getString(R.string.desync_notification_title))
            .setContentText(getString(R.string.desync_notification_text))
            .setSmallIcon(R.drawable.ic_tile_shield)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_disable), stop)
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
