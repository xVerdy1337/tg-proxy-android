package com.tgwsproxy.vpn

import com.tgwsproxy.net.StrategyTester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DesyncVpnService.buildByedpiArgs] is the only checkpoint between a pasted byedpi command line
 * and the native engine, and both of its failure modes are silent:
 *
 *  - too permissive → a smuggled "-i 0.0.0.0" survives and *wins* (getopt keeps the LAST value),
 *    publishing the auth-less SOCKS5 listener to the whole LAN; a "-C host:port" reroutes every
 *    proxied connection through a stranger's box without a trace in the UI;
 *  - too aggressive → a desync flag is eaten, the strategy degrades to "no desync at all" and the
 *    product simply stops unblocking anything, with no error anywhere.
 *
 * The blocked set is keyed on PRIMITIVES rather than on individual flag names, which is why two of
 * the letters look harmless until you follow the value:
 *
 *  - ftob() (byedpi/main.c) fopen()s its argument and slurps the whole file unless it starts with
 *    ':'. It has exactly three call sites — -H/--hosts, -j/--ipset and -l/--fake-data — and all
 *    three are blocked. -l is not merely a read: desync.c hands those bytes to the destination host
 *    as the decoy payload, at a TTL the same command picks with -t, so one flag turns "read a local
 *    file" into "ship it to a server I chose". Any future option that reaches ftob() belongs here.
 *  - -B/--copy assigns optind to replay an earlier option group, and only a later -A advances it
 *    again. "-B 1" rewinds to the initial group, whose _optind is 0, so parse_args re-reads argv
 *    for the life of the process — the engine thread wedges without ever publishing its socket, and
 *    the bypass stays broken until the app is force-stopped. Availability, not confidentiality, but
 *    just as invisible as the rest.
 *
 * The spec the filter implements is "what getopt and byedpi actually accept", not "what a
 * well-formed command looks like". Both of the obvious narrowings are bypassable:
 *
 *  - Glued short form must key on the FLAG LETTER, never on the shape of the value. byedpi feeds
 *    -i's value to get_addr_scheme()/get_addr() (byedpi/main.c), which peel a
 *    "socks5://"/"tcp://"/"http://" scheme prefix and accept a bracketed literal — so "-i[::]" and
 *    "-isocks5://0.0.0.0" walk straight past a digit/'.'/':' whitelist and still bind every
 *    interface.
 *  - Long options must be matched by PREFIX, because getopt_long resolves any *unambiguous*
 *    abbreviation: "--connect" reaches connect-to, "--pid" reaches pidfile, "--host" reaches hosts,
 *    "--cache-f" reaches cache-file, "--ips" reaches ipset, "--da" reaches daemon.
 *  - Shorts CLUSTER, so a single-dash token has to be decomposed the way getopt would rather than
 *    matched on its first letter. byedpi builds its optstring straight from options[]
 *    (byedpi/main.c) with no leading '+'/'-', so "-Ni 0.0.0.0" is -N (no-domain, boolean) followed
 *    by -i taking the NEXT argv — the same total bypass as a bare "-i", just wearing a harmless
 *    boolean in front. Decomposition stops at the first letter with has_arg == 1, because that
 *    letter swallows the remainder of the token as its inline value: in "-nwww.ip.example" the
 *    "ip" is hostname bytes, not flags, and dropping that token would eat a real strategy flag.
 *
 * Prefix matching is the half that can over-reach, so the keep-side tests enumerate every long flag
 * in options[] (byedpi/main.c) that merely *resembles* a blocked one — --proto vs --protect-path,
 * --cache-ttl/--cache-merge vs --cache-file, --conn-ip vs --ip, --comment vs --connect-to,
 * --pf vs --port — plus the whole shipped catalog.
 *
 * Blocking --fake-data broke the invariant those pairs rest on, that no kept name is a prefix of a
 * blocked one: --fake (-f) sits inside --fake-data (-l). getopt_long resolves an EXACT long name
 * before it considers any abbreviation, so --fake must still reach -f, and the filter carries that
 * single exception by name. No shipped preset spells --fake the long way, so the catalog test
 * cannot see it disappear — theExactLongNameFakeSurvivesAlthoughItIsAPrefixOfFakeData is the only
 * thing standing between a dropped exception and every fake-based strategy dying in silence.
 *
 * Every test below is therefore one of two shapes: "the hostile token is gone" or "the legitimate
 * token is still there, byte for byte".
 *
 * The file's second subject is [ByedpiPresetCatalog.migrateCommand], which sits one step earlier on
 * the same launch path (DesyncVpnService.loadPrefs) and reasons about a command with this file's
 * tokeniser and this file's letter table. Its two failure modes mirror the ones above:
 *
 *  - too shy → a persisted -A command keeps a provably inert auto-detect (params.timeout and
 *    ptimeout are written by case 'T' alone, byedpi/main.c, and default to 0), and because every
 *    group *other* than -A behaves exactly as before, the string still passes an auto-tune sweep and
 *    re-elects itself ahead of the fixed catalog entry — the defect pins itself in place;
 *  - too eager → a command with NO -A group gets a global params.timeout it never asked for, and
 *    having no detect group it also has no second group to fall back to, so what used to be a stall
 *    the kernel rides out becomes a hard reset.
 *
 * Byte-exactness is the third mode and the quietest: the output is compared as TEXT against the
 * catalog by [ByedpiPresetCatalog.byCommand] and [StrategyTester.labelResForCommand], so a single
 * rewritten space inside a quoted value relabels a shipped strategy as a custom command in the UI
 * and in every support report that follows. That is why migrateCommand prepends to the original
 * string instead of re-joining tokens, and why the tests below assert on whole strings.
 */
class ByedpiArgsTest {

    private companion object {
        const val IP = "127.0.0.1"
        const val PORT = 1080

        /**
         * The prefix buildByedpiArgs always emits; the strategy is everything after it. -U is part
         * of the pin, not decoration: it turns byedpi's SOCKS5 UDP path off, and that path is where
         * a negative --fake-offset reads out of bounds and ships the result to the peer. params.udp
         * is only ever cleared by the engine, never set, so no later token can undo it.
         */
        val PINNED = listOf("ciadpi", "-i", IP, "-p", PORT.toString(), "-U")
    }

    private fun args(command: String): List<String> =
        DesyncVpnService.buildByedpiArgs(command, IP, PORT).toList()

    @Test
    fun effectivePresetUsesCatalogForEmptyCustomCommand() {
        assertEquals(DesyncVpnService.PRESET_AUTO,
            DesyncVpnService.effectivePresetFor(DesyncVpnService.PRESET_AUTO, ""))
    }

    @Test
    fun effectivePresetRecognizesMigratedCatalogCommand() {
        val legacy = ByedpiPresetCatalog.commandFor(DesyncVpnService.PRESET_AUTO)
            .removePrefix("-T10 ")
        assertEquals(DesyncVpnService.PRESET_AUTO,
            DesyncVpnService.effectivePresetFor(DesyncVpnService.PRESET_AUTO, legacy))
    }

    @Test
    fun effectivePresetMarksUnknownCommandAsCustom() {
        assertEquals("custom",
            DesyncVpnService.effectivePresetFor(DesyncVpnService.PRESET_AUTO, "-s 999"))
    }

    /** The strategy tail — what actually reaches getopt after our pinned listen endpoint. */
    private fun strategy(command: String): List<String> = args(command).drop(PINNED.size)

    private fun words(command: String): List<String> =
        command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Every command we ship, from both sources, catalog "off" (the empty one) included. */
    private fun shippedCommands(): List<String> =
        (ByedpiPresetCatalog.presets.map { it.command } + StrategyTester.STRATEGIES.map { it.command })
            .distinct()

    /**
     * Every command we ship that opens an -A group. Both sources are checked together because both
     * feed the same buildByedpiArgs and the same engine — a strategy that tunes differently from
     * how it later runs is worse than no auto-tune at all.
     */
    private fun shippedAutoCommands(): List<String> =
        shippedCommands().filter { c -> words(c).any { it.startsWith("-A") } }

    /** The raw -T value ("2:2:2:64") of [command], in every spelling getopt would accept. */
    private fun timeoutValue(command: String): String? {
        val w = words(command)
        for ((i, t) in w.withIndex()) {
            val value = when {
                t == "-T" || t == "--timeout" -> w.getOrNull(i + 1)
                t.startsWith("--timeout=") -> t.removePrefix("--timeout=")
                // Case-sensitive: -t is the fake TTL and carries no timeouts at all.
                t.startsWith("-T") -> t.removePrefix("-T")
                else -> null
            }
            if (value != null) return value
        }
        return null
    }

    /** The words of [command] that hand getopt a -T value, in every spelling this app writes. */
    private fun timeoutTokens(command: String): List<String> =
        words(command).filter {
            // Case-sensitive, as everywhere: -t is the fake TTL and carries no timeouts at all.
            it.startsWith("-T") || it == "--timeout" || it.startsWith("--timeout=")
        }

    /**
     * [command] with its -T token — and, in the separate spelling, that token's value — removed.
     *
     * The superseded spelling is DERIVED rather than pasted in from the old catalog, for the same
     * reason migrateCommand matches on shape instead of on a table of literals: an old literal stops
     * describing anything the moment the catalog moves on, and the test would then keep passing
     * while asserting nothing about what users actually have on disk.
     */
    private fun withoutTimeout(command: String): String {
        val w = words(command)
        val out = ArrayList<String>(w.size)
        var i = 0
        while (i < w.size) {
            val t = w[i]
            if (t == "-T" || t == "--timeout") {
                // Separate-value spelling: the value is the next word and leaves with the flag.
                i++
            } else if (!t.startsWith("-T") && !t.startsWith("--timeout=")) {
                out.add(t)
            }
            i++
        }
        return out.joinToString(" ")
    }

    @Test
    fun argvStartsWithCiadpiAndThePinnedListenEndpoint() {
        assertEquals(PINNED + listOf("-d1", "-s1+s"), args("-d1 -s1+s"))

        // The caller picks the port from SOCKS_PORT_CANDIDATES when 1080 is taken, so the pin has
        // to follow the arguments rather than a hard-coded constant.
        assertEquals(
            listOf("ciadpi", "-i", "127.0.0.1", "-p", "28080", "-U", "-d1"),
            DesyncVpnService.buildByedpiArgs("-d1", "127.0.0.1", 28080).toList(),
        )
    }

    /**
     * The UDP kill-switch is a security control, so pin it explicitly rather than leaving it
     * implied by [PINNED]. A user command carrying -a/--udp-fake must not read as re-enabling it:
     * byedpi has no flag that sets params.udp back, and -a only sets the fake COUNT.
     */
    @Test
    fun udpIsPinnedOffAndNoUserTokenCanTurnItBackOn() {
        assertTrue("-U" in args("-d1 -s1+s"))
        assertTrue("-U" in args(""))
        // -a1 rides along in most catalog presets; it stays (it is inert with UDP off) but -U must
        // still precede the strategy tail, where getopt's last-wins cannot be used against us.
        val out = args("-d1 -a1")
        assertEquals(PINNED + listOf("-d1", "-a1"), out)
        assertTrue(out.indexOf("-U") < out.indexOf("-a1"))
    }

    @Test
    fun emptyCommandYieldsOnlyThePinnedEndpoint() {
        assertEquals(PINNED, args(""))
        assertEquals(PINNED, args("   \t  "))
    }

    @Test
    fun listenFlagsAreStrippedInSeparateArgumentForm() {
        assertEquals(listOf("-d1"), strategy("-i 0.0.0.0 -d1"))
        assertEquals(listOf("-d1"), strategy("-d1 -i 0.0.0.0"))
        assertEquals(listOf("-d1"), strategy("--ip 0.0.0.0 -d1"))
        assertEquals(listOf("-d1"), strategy("-p 9999 -d1"))
        assertEquals(listOf("-d1"), strategy("--port 9999 -d1"))

        // A dangling flag at the end has no value to consume; it must still not reach getopt,
        // where it would swallow whatever we appended after it.
        assertEquals(listOf("-d1"), strategy("-d1 -i"))
    }

    @Test
    fun listenFlagsAreStrippedInLongEqualsForm() {
        assertEquals(listOf("-d1"), strategy("--ip=0.0.0.0 -d1"))
        assertEquals(listOf("-d1"), strategy("--port=9999 -d1"))
    }

    @Test
    fun gluedShortListenFlagCannotHideBehindASchemeOrBrackets() {
        // The value is never inspected, only the flag letter, because byedpi's own parser accepts
        // far more than an address literal: get_addr_scheme() strips a "socks5://"/"tcp://"/...
        // prefix and get_addr() unwraps "[...]", so every one of these binds 0.0.0.0 / :: and would
        // have sailed past a character whitelist on the first value byte.
        val hostile = listOf(
            "-i[::]", "-i[0.0.0.0]", "-isocks5://0.0.0.0", "-itcp://0.0.0.0",
            "-ihttp://0.0.0.0", "-isocks5://[::]", "-i0.0.0.0", "-i192.168.1.5", "-i::",
            "-p1080", "-p9999",
        )
        for (flag in hostile) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
            // Trailing position is the dangerous one — getopt is last-wins.
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("-d1 $flag"))
        }
    }

    @Test
    fun aBlockedShortCannotHideBehindBooleansInACluster() {
        // Every pair below ends the token on a value-taking blocked letter, so getopt takes the
        // FOLLOWING argv as its value: the flag and the value both have to disappear, and the
        // desync flags on either side have to stay. Losing the harmless boolean that rides in
        // front (-N/-X/-U/-Z) is deliberate collateral — dropping the whole token is the only
        // reading that cannot be gamed.
        val clustered = listOf(
            "-Ni" to "0.0.0.0",            // no-domain  + --ip           → binds every interface
            "-Xi" to "0.0.0.0",            // no-ipv6    + --ip
            "-Zp" to "1080",               // wait-send  + --port
            "-UC" to "attacker.tld:443",   // no-udp     + --connect-to   → MITM every connection
            "-XP" to "/data/local/tmp/s",  // no-ipv6    + --protect-path
            "-NH" to "/etc/passwd",        // no-domain  + --hosts        → arbitrary read
            "-Xy" to "/sdcard/x",          // no-ipv6    + --cache-file   → arbitrary write
            "-Nw" to "/sdcard/pid",        // no-domain  + --pidfile
            "-Uj" to "/sdcard/ipset",      // no-udp     + --ipset
        )
        for ((flag, value) in clustered) {
            assertEquals(
                "'$flag $value' must not survive",
                listOf("-d1", "-s1+s"),
                strategy("-d1 $flag $value -s1+s"),
            )
        }

        // A blocked BOOLEAN (-D daemon, -E transparent) closing a cluster consumes no argv, so the
        // next token is strategy and must be kept.
        for (flag in listOf("-XD", "-ND", "-UE", "-NE")) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
        }
    }

    @Test
    fun aClusteredBlockedShortWithAnInlineValueDropsOnlyItsOwnToken() {
        // Same shape with the value glued on: the blocked letter eats the rest of the token, so the
        // next token is strategy. Dropping it too would be the other failure mode — a silently
        // shortened desync.
        val inline = listOf(
            "-Ni[::]", "-Xi0.0.0.0", "-Zp1080", "-Nisocks5://0.0.0.0",
            "-NXUi0.0.0.0", "-UCattacker.tld:443", "-Xy/sdcard/x", "-NHi",
        )
        for (flag in inline) {
            assertEquals(
                "'$flag' must not survive",
                listOf("-d1", "-s1+s"),
                strategy("-d1 $flag -s1+s"),
            )
        }
    }

    @Test
    fun aClusterIsWalkedPastEveryBooleanBeforeItIsCleared() {
        // Any number of booleans may precede the blocked letter, so the walk cannot stop early…
        assertEquals(listOf("-d1", "-s1+s"), strategy("-d1 -NXUi 0.0.0.0 -s1+s"))
        assertEquals(listOf("-d1", "-s1+s"), strategy("-d1 -NXUC 1.2.3.4:443 -s1+s"))
        // …and a cluster that never reaches one is legitimate byedpi (no-domain/no-ipv6/no-udp,
        // all has_arg == 0), so it must reach the engine intact.
        assertEquals(listOf("-NXU", "-d1"), strategy("-NXU -d1"))
        assertEquals(listOf("-NX", "-d1"), strategy("-NX -d1"))
    }

    @Test
    fun aClusterWhoseFirstLetterTakesAValueIsDataNotFlags() {
        // The boundary that keeps the decomposition safe. getopt hands everything after the first
        // has_arg == 1 letter to that option as its inline value, so those bytes are never flags —
        // each token below carries an 'i'/'p'/'C'/'P' inside a value, and treating it as a flag
        // would strip a legitimate strategy.
        val keep = listOf(
            "-nwww.ip.example",  // --fake-sni: a hostname, not -i/-p
            "-eip",              // --oob-data: two literal payload bytes
            "-Qmsize=1080",      // --fake-tls-mod
            "-QCiPy",            // --fake-tls-mod again, with a value made of nothing but blocked
                                 // letters — none of them is ever looked at
            "-I10.0.0.1",        // --conn-ip (the *source* address) — case matters, -I is not -i
            "-L1",               // --auto-mode — case matters too, -L is not -l (--fake-data)
        )
        for (flag in keep) {
            assertEquals(
                "'$flag' was eaten by the listen-flag filter",
                listOf(flag, "-d1"),
                strategy("$flag -d1"),
            )
            assertEquals(
                "'$flag' was eaten by the listen-flag filter",
                listOf("-d1", flag),
                strategy("-d1 $flag"),
            )
        }

        // The separate-value spelling of the same option: the value is its own token and stays put.
        assertEquals(listOf("-n", "www.ip.example", "-d1"), strategy("-n www.ip.example -d1"))
    }

    @Test
    fun longOptionsAreStrippedByAbbreviationBecauseGetoptLongResolvesThem() {
        // Exact-name matching was not a filter at all: each abbreviation below is unambiguous in
        // options[] (byedpi/main.c) and therefore reaches the blocked option inside the engine.
        val valueTaking = listOf(
            "--connect 1.2.3.4:443",      // → connect-to, i.e. MITM every proxied connection
            "--connect-t 1.2.3.4:443",
            "--pid /tmp/x",               // → pidfile, arbitrary write
            "--host /etc/passwd",         // → hosts, arbitrary read
            "--cache-f /tmp/x",           // → cache-file
            "--ips /tmp/x",               // → ipset
            "--protect /tmp/sock",        // → protect-path ("proto" is not a prefix of it)
            "--po 9999",                  // → port
        )
        for (flag in valueTaking) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
        }

        // The inline-value spelling of the same abbreviations: the following token is strategy and
        // must NOT be eaten along with the flag.
        val valueGlued = listOf(
            "--connect=1.2.3.4:443", "--pid=/tmp/x", "--host=/etc/passwd",
            "--cache-f=/tmp/x", "--ips=/tmp/x", "--po=9999",
        )
        for (flag in valueGlued) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
        }

        // Boolean abbreviations take no value, so the next token is strategy in every case.
        val boolAbbrev = listOf("--da", "--daem", "--trans", "--transp")
        for (flag in boolAbbrev) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
        }
    }

    @Test
    fun theLastListenFlagCanNeverWin() {
        // getopt is last-wins, so the only safe outcome is that no second -i/-p exists at all.
        val hostile = "-d1 -i 0.0.0.0 -p 9999 --ip=0.0.0.0 -i0.0.0.0 -p1080 --port 9999 " +
            "-i[::] -isocks5://0.0.0.0 --po=1234 -s1+s"
        val out = args(hostile)

        assertEquals(PINNED + listOf("-d1", "-s1+s"), out)
        assertFalse("a bind address other than the pin leaked through", out.contains("0.0.0.0"))
        assertEquals(
            "exactly one -i and one -p may reach getopt, in that order",
            listOf("-i", "-p"),
            out.filter { it == "-i" || it == "-p" },
        )
    }

    @Test
    fun quotingCannotSmuggleAListenFlagPastTheFilter() {
        // The tokeniser runs first, so quotes are gone by the time the filter sees the tokens.
        assertEquals(listOf("-d1"), strategy("'-i' '0.0.0.0' -d1"))
        assertEquals(listOf("-d1"), strategy("\"-p\" \"9999\" -d1"))
        assertEquals(listOf("-d1"), strategy("-i'[::]' -d1"))
        assertEquals(listOf("-d1"), strategy("\"--connect\" 1.2.3.4:443 -d1"))
    }

    @Test
    fun fileAndDaemonFlagsAreStripped() {
        // A DPI strategy has no business reading or writing the filesystem, nor detaching the
        // engine from the service that is supposed to own its lifetime.
        val separate = listOf(
            "-y /sdcard/cache", "--cache-file /sdcard/cache",
            "-w /sdcard/pid", "--pidfile /sdcard/pid",
            "-H /etc/hosts", "--hosts /etc/hosts",
            "-j /sdcard/ipset", "--ipset /sdcard/ipset",
        )
        for (flag in separate) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
        }

        val longGlued = listOf(
            "--cache-file=/sdcard/cache", "--pidfile=/sdcard/pid",
            "--hosts=/etc/hosts", "--ipset=/sdcard/ipset",
        )
        for (flag in longGlued) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
        }

        val shortGlued = listOf("-y/sdcard/cache", "-w/sdcard/pid", "-H/etc/hosts", "-j/sdcard/ipset")
        for (flag in shortGlued) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
        }

        val boolFlags = listOf("-D", "--daemon", "-E", "--transparent")
        for (flag in boolFlags) {
            // These take no value, so the token after them is strategy and must be kept.
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
        }
    }

    @Test
    fun connectToAndProtectPathFlagsAreStripped() {
        // byedpi's option table (app/src/main/cpp/byedpi/main.c) also exposes
        //   {"connect-to", 1, 0, 'C'}   — force every connection to a fixed host:port, i.e. an
        //                                 attacker-chosen MITM for all of the user's traffic
        //   {"protect-path", 1, 0, 'P'} — the socket-protect helper path; repointing it breaks the
        //                                 VPN loop protection (or points it at a foreign socket)
        // Both take a value, so all three spelling forms have to go.
        assertEquals(listOf("-d1"), strategy("-C 1.2.3.4:443 -d1"))
        assertEquals(listOf("-d1"), strategy("--connect-to 1.2.3.4:443 -d1"))
        assertEquals(listOf("-d1"), strategy("--connect-to=1.2.3.4:443 -d1"))
        assertEquals(listOf("-d1"), strategy("-C1.2.3.4:443 -d1"))
        assertEquals(listOf("-d1"), strategy("-Csocks5://1.2.3.4:443 -d1"))

        assertEquals(listOf("-d1"), strategy("-P /data/local/tmp/sock -d1"))
        assertEquals(listOf("-d1"), strategy("--protect-path /data/local/tmp/sock -d1"))
        assertEquals(listOf("-d1"), strategy("--protect-path=/data/local/tmp/sock -d1"))
        assertEquals(listOf("-d1"), strategy("-P/data/local/tmp/sock -d1"))
    }

    @Test
    fun fakeDataIsStrippedInEveryFormBecauseItReadsAndThenShipsTheFile() {
        // -l/--fake-data is the third ftob() call site (byedpi/main.c, next to -H and -j) and the
        // only one whose bytes leave the device: what ftob() slurps becomes dp->fake_data, and
        // desync.c sends that as the decoy packet to the destination host — at the TTL the same
        // command picks with -t. "-l /data/misc/keystore -t 64" is an exfiltration primitive
        // wearing a strategy flag, so it has to go in every spelling getopt would accept.
        val separate = listOf(
            "-l /data/misc/keystore",
            "--fake-data /data/misc/keystore",
            // Nothing else in options[] starts "fake-d", so getopt_long resolves all of these.
            "--fake-d /data/misc/keystore",
            "--fake-da /data/misc/keystore",
            "--fake-dat /data/misc/keystore",
        )
        for (flag in separate) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
            assertEquals(
                "'$flag' must not survive",
                listOf("-d1", "-s1+s"),
                strategy("-d1 $flag -s1+s"),
            )
        }

        // Value glued on: the flag eats only its own token, so the next one is strategy. "-l:CiPy"
        // is the honest cost of keying on the letter — a ':' prefix means "literal bytes, no
        // fopen()" to ftob(), so that spelling was never a read, and it goes anyway. The letter is
        // blocked as a primitive; nothing shipped spells -l at all, and every rule that reads the
        // value before deciding is how the -i[::] bypass got in.
        val glued = listOf(
            "-l/data/misc/keystore", "-l:CiPy",
            "--fake-data=/data/misc/keystore", "--fake-d=/data/misc/keystore",
        )
        for (flag in glued) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
        }

        // Clustered behind booleans, both spellings. "-NXUl /path" ends on the blocked letter, so
        // getopt takes the NEXT argv as the filename and it has to go too; "-Xl/path" carries the
        // filename inline, so the following token is strategy and must stay.
        assertEquals(listOf("-d1", "-s1+s"), strategy("-d1 -NXUl /data/misc/keystore -s1+s"))
        assertEquals(listOf("-d1", "-s1+s"), strategy("-d1 -Ul /data/misc/keystore -s1+s"))
        assertEquals(listOf("-d1", "-s1+s"), strategy("-d1 -Xl/data/misc/keystore -s1+s"))
        assertEquals(listOf("-d1", "-s1+s"), strategy("-d1 -NXUl:CiPy -s1+s"))

        // Trailing, with nothing left to consume: it must still not reach getopt, where it would
        // swallow whatever we appended after it.
        assertEquals(listOf("-d1"), strategy("-d1 -l"))
        assertEquals(listOf("-d1"), strategy("-d1 --fake-data"))

        // Every spelling at once — no path and no -l in any form may reach argv.
        val out = args("-d1 -l /etc/hosts --fake-data=/etc/hosts -NXUl /etc/hosts -l:CiPy -s1+s")
        assertEquals(PINNED + listOf("-d1", "-s1+s"), out)
    }

    @Test
    fun copyIsStrippedBecauseItCanRewindGetoptForever() {
        // -B/--copy replays an earlier option group by assigning optind (byedpi/main.c), and only a
        // later -A advances it again. "-B 1" rewinds to the initial group, whose _optind is 0, so
        // parse_args re-reads argv from the top for the life of the process. The engine thread
        // never publishes server_fd, so we can neither stop it nor start another — one pasted
        // string bricks the bypass until the app is force-stopped. The only blocked flag that is
        // dangerous for what it does to *us* rather than to the user's data.
        val separate = listOf("-B 1", "-B 2", "-B i", "--copy 1", "--cop 1")
        for (flag in separate) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("-d1 $flag"))
            assertEquals(
                "'$flag' must not survive",
                listOf("-d1", "-s1+s"),
                strategy("-d1 $flag -s1+s"),
            )
        }

        val glued = listOf("-B1", "-Bi", "--copy=1", "--cop=1")
        for (flag in glued) {
            assertEquals("'$flag' must not survive", listOf("-d1"), strategy("$flag -d1"))
        }

        // Clustered, with the same split between "the value is the next argv" and "the value is
        // glued on" that every other value-taking blocked letter has.
        assertEquals(listOf("-d1", "-s1+s"), strategy("-d1 -NXUB 1 -s1+s"))
        assertEquals(listOf("-d1", "-s1+s"), strategy("-d1 -XB1 -s1+s"))
        assertEquals(listOf("-d1"), strategy("-d1 -B"))

        // Case matters, as everywhere else in the letter set: -b is --buf-size and rewinds nothing.
        assertEquals(listOf("-b16384", "-d1"), strategy("-b16384 -d1"))
        assertEquals(listOf("-b", "16384", "-d1"), strategy("-b 16384 -d1"))
    }

    @Test
    fun theExactLongNameFakeSurvivesAlthoughItIsAPrefixOfFakeData() {
        // The regression that would silently disable the product. Blocking --fake-data put a kept
        // name inside a blocked one for the first time, and a plain prefix rule would carry off
        // --fake with it — the flag every aggressive preset is built on. getopt_long settles an
        // exact long name before it goes looking for an abbreviation, so --fake reaches -f and only
        // the longer names reach -l. Nothing else in the suite would notice this exception going.
        assertEquals(listOf("--fake", "-1", "-d1"), strategy("--fake -1 -d1"))
        assertEquals(listOf("--fake=-1", "-d1"), strategy("--fake=-1 -d1"))
        assertEquals(listOf("-d1", "--fake", "-1"), strategy("-d1 --fake -1"))
        assertEquals(listOf("--fake", "2", "-t8", "-s1+s"), strategy("--fake 2 -t8 -s1+s"))

        // The short spelling is the one the catalog ships, in every offset form it uses.
        val shorts = listOf("-f-1", "-f1+nme", "-f2", "-f-1+s", "-f50+e")
        for (flag in shorts) {
            assertEquals(
                "'$flag' was eaten by the listen-flag filter",
                listOf(flag, "-t8", "-d1"),
                strategy("$flag -t8 -d1"),
            )
        }

        // The other three --fake-* names diverge from --fake-data before it ends, so the ordinary
        // prefix rule already keeps them — but they carry the rest of the fake payload's shape, and
        // an over-broad "anything starting --fake" block would surface here first.
        val fakeLong = listOf(
            "--fake-sni" to "www.google.com",
            "--fake-offset" to "1",
            "--fake-tls-mod" to "rand",
        )
        for ((flag, value) in fakeLong) {
            assertEquals(
                "'$flag' was eaten by the listen-flag filter",
                listOf(flag, value, "-d1"),
                strategy("$flag $value -d1"),
            )
            assertEquals(
                "'$flag=$value' was eaten by the listen-flag filter",
                listOf("$flag=$value", "-d1"),
                strategy("$flag=$value -d1"),
            )
        }
    }

    @Test
    fun everyCatalogPresetSurvivesUnchanged() {
        // The regression that matters most: an over-broad filter would leave the VPN running and
        // the UI green while quietly stripping the desync out of every shipped preset.
        assertTrue("an emptied catalog would make this whole test vacuous",
            ByedpiPresetCatalog.presets.size > 1)
        for (preset in ByedpiPresetCatalog.presets) {
            val expected = words(preset.command)
            val actual = strategy(preset.command)
            assertEquals(
                "preset '${preset.id}' was mangled by the listen-flag filter",
                expected,
                actual,
            )
            // Verbatim also means "nothing shifted": the pin must still be exactly argv[0..4].
            assertEquals(
                "preset '${preset.id}' disturbed the pinned listen endpoint",
                PINNED + expected,
                args(preset.command),
            )
            // "off" is the one preset that is *supposed* to reduce to nothing; for every other,
            // an empty tail means the desync was silently removed.
            if (preset.id != DesyncVpnService.PRESET_OFF) {
                assertTrue("preset '${preset.id}' lost its entire strategy", actual.isNotEmpty())
            }
        }

        // The catalog only ever spells fake the short way, so it cannot see the exact-name
        // exception for --fake disappear; it does pin -f itself, which is one letter away from the
        // newly blocked -l and shares the -t that made -l worth blocking.
        assertTrue(
            "no preset carries -f any more — the fake keep-side tests cover nothing we ship",
            ByedpiPresetCatalog.presets.any { p -> words(p.command).any { it.startsWith("-f") } },
        )
    }

    @Test
    fun everyDesyncFlagSurvivesVerbatim() {
        // One command carrying the whole vocabulary the catalog uses: -d disorder, -s split,
        // -r tlsrec, -f fake, -t fake TTL, -o oob, -q disoob, -a udp-fake, -A auto, -T the
        // timeouts -A needs to detect anything, plus the "+s" suffix that anchors an offset to the
        // SNI.
        val command = "-T2:2:2:64 -d1 -s1+s -r1+s -f-1 -t8 -o1 -q4+hm -a1 -At,r,s -s50+s"
        val out = strategy(command)

        assertEquals(words(command), out)
        for (flag in listOf("-d", "-s", "-r", "-f", "-t", "-o", "-q", "-a", "-A", "-T")) {
            assertTrue("$flag was eaten by the listen-flag filter", out.any { it.startsWith(flag) })
        }
        assertEquals(
            "the +s SNI offsets must reach the engine untouched",
            listOf("-s1+s", "-r1+s", "-s50+s"),
            out.filter { it.endsWith("+s") },
        )
    }

    @Test
    fun theAutoDetectTimeoutSurvivesInEveryFormAndShipsWithEveryAutoStrategy() {
        // -T/--timeout is the one flag whose loss would not look like damage anywhere: the command
        // still parses, -A still builds its groups, and nothing ever detects anything. case 'T'
        // (byedpi/main.c) is the only writer of params.timeout / ptimeout / to_count_lim /
        // to_bytes_lim, all of which default to 0, and with them at 0 an -A group has no trigger
        // against a silent SNI drop — no RST for 't', no FIN for 's', no redirect for 'r', and
        // connect() succeeds so 'c' never fires either. 'T' is in VALUE_SHORT_LETTERS, out of
        // BLOCKED_SHORT_LETTERS, and "timeout" is a prefix of no blocked long name; these pin that
        // down in every spelling getopt would accept.
        assertEquals(listOf("-T", "2:2:2:64", "-d1"), strategy("-T 2:2:2:64 -d1"))
        assertEquals(listOf("-T2:2:2:64", "-d1"), strategy("-T2:2:2:64 -d1"))
        assertEquals(listOf("--timeout", "2:2:2:64", "-d1"), strategy("--timeout 2:2:2:64 -d1"))
        assertEquals(listOf("--timeout=2:2:2:64", "-d1"), strategy("--timeout=2:2:2:64 -d1"))
        // Trailing (nothing follows for a value-dropping rule to eat) and clustered behind
        // booleans, in both the inline and the next-argv spelling — the shapes that trip a filter
        // keyed on the token's first letter instead of on the cluster walk.
        assertEquals(listOf("-d1", "-T2:2:2:64"), strategy("-d1 -T2:2:2:64"))
        assertEquals(listOf("-d1", "-T", "2:2:2:64"), strategy("-d1 -T 2:2:2:64"))
        assertEquals(listOf("-NXUT2:2:2:64", "-d1"), strategy("-NXUT2:2:2:64 -d1"))
        assertEquals(listOf("-NXUT", "2:2:2:64", "-d1"), strategy("-NXUT 2:2:2:64 -d1"))

        // The premise that makes all of the above worth asserting, and the regression that would
        // put the "auto" strategies back to being auto in name only: every command we ship with an
        // -A group carries the timeouts that group needs, and survives the filter byte for byte.
        val autoCommands = shippedAutoCommands()

        assertTrue("nothing ships -A any more — this test now covers nothing",
            autoCommands.isNotEmpty())
        for (command in autoCommands) {
            assertTrue(
                "'$command' arms -A's auto-detect with no -T, so no detector can ever fire",
                words(command).any { it.startsWith("-T") || it.startsWith("--timeout") },
            )
            assertEquals(
                "'$command' was mangled by the listen-flag filter",
                words(command),
                strategy(command),
            )
        }
    }

    /**
     * The -T first field and [StrategyTester.TLS_TIMEOUT_MS] are one setting spread across two
     * files, and pulling them apart fails in the direction nobody looks: the command still parses,
     * the engine still recovers in production, and only the auto-tuner — the thing that decides
     * whether the user is ever offered the strategy — records a dead host.
     *
     * Field 1 is armed as TCP_USER_TIMEOUT on the *upstream* socket (extend.c setup_conn), so when
     * the DPI eats the ClientHello nothing happens on the tester's socket at all until it fires;
     * only then does handle_err → on_torst → DETECT_TORST move to the next -A group and replay the
     * saved ClientHello. Detect, reconnect and a complete second handshake all have to land inside
     * one soTimeout window, which is why the margin below is not decoration.
     *
     * Field 4 is the same coupling seen from the other side: extend.c lifts that socket timeout
     * only once recv_count passes to_bytes_lim, so it decides whether the aggressive deadline
     * covers the pre-first-response window it was added for or rides every connection the preset
     * carries for its whole life.
     */
    @Test
    fun everyAutoStrategyDetectsWellInsideTheTestersOwnPatience() {
        // Room for the reconnect plus a full replayed handshake on a slow mobile link. Nothing in
        // the engine enforces it — it is the floor we refuse to ship below.
        val replayHeadroomMs = 2_000

        val autoCommands = shippedAutoCommands()
        assertTrue("nothing ships -A any more — this test now covers nothing",
            autoCommands.isNotEmpty())

        for (command in autoCommands) {
            val value = timeoutValue(command)
            assertNotNull("'$command' arms -A with no -T value this test can read", value)
            val fields = value!!.split(":")

            // case 'T' (byedpi/main.c) reads field 1 with strtof, in SECONDS, into params.timeout.
            val detectMs = ((fields[0].toFloatOrNull() ?: -1f) * 1000).toInt()
            assertTrue("'$command' has an unparseable -T detect deadline", detectMs > 0)
            assertTrue(
                "'$command' detects at ${detectMs}ms, but the tester stops reading at " +
                    "${StrategyTester.TLS_TIMEOUT_MS}ms — no detector can fire before the probe " +
                    "gives up, so this strategy scores dead in every sweep however well it works",
                detectMs + replayHeadroomMs <= StrategyTester.TLS_TIMEOUT_MS,
            )

            // to_bytes_lim: 0 (or an absent field) never releases the socket timeout, and a large
            // one releases it only on connections that happen to pull that much down. A bare TLS
            // ServerHello record already clears the bound below, so the release lands on the
            // server's first packet rather than somewhere inside a long-lived connection.
            val bytesLim = fields.getOrNull(3)?.toFloatOrNull()?.toInt() ?: 0
            assertTrue(
                "'$command' leaves -T's socket timeout armed for the life of every connection " +
                    "under $bytesLim bytes — an ACK blackout then RSTs it, or caches a bogus " +
                    "DETECT_TORST against the destination IP",
                bytesLim in 1..256,
            )
        }
    }

    @Test
    fun longFlagsThatMerelyResembleABlockedNameSurvive() {
        // The prefix match is the over-reaching half of the filter, so pin down every long name in
        // options[] (byedpi/main.c) that shares a head with a blocked one. Each pair diverges
        // before the blocked name ends, so none of these is a prefix of it:
        //   --proto ('K') vs --protect-path — 5th char 'o' vs 'e'
        //   --cache-ttl ('u') / --cache-merge ('/') vs --cache-file — 7th char
        //   --conn-ip ('I', the *source* address) / --comment ('#') vs --connect-to — 5th/4th char
        //   --pf ('V') vs --port / --pidfile / --protect-path — 3rd char
        //   --debug ('x') vs --daemon — 2nd char
        // --copy used to sit in this list; it is blocked outright now (see the getopt-rewind test).
        val keep = listOf(
            "--proto" to "t",
            "--cache-ttl" to "60",
            "--cache-merge" to "1",
            "--conn-ip" to "0.0.0.0",
            "--comment" to "note",
            "--pf" to "443",
            "--debug" to "1",
            "--ttl" to "8",
            "--tlsrec" to "1+s",
            "--fake-sni" to "www.google.com",
        )
        for ((flag, value) in keep) {
            assertEquals(
                "'$flag' was eaten by the listen-flag filter",
                listOf(flag, value, "-d1"),
                strategy("$flag $value -d1"),
            )
            assertEquals(
                "'$flag=$value' was eaten by the listen-flag filter",
                listOf("$flag=$value", "-d1"),
                strategy("$flag=$value -d1"),
            )
        }
    }

    @Test
    fun gluedShortValuesOfNonBlockedFlagsSurvive() {
        // Matching on the flag letter alone means the letter set has to be exactly right, and it is
        // case-sensitive: -I (--conn-ip) must not fall into -i's blast radius, nor -Q/-V into -P/-p.
        assertEquals(listOf("-nwww.google.com", "-d1"), strategy("-nwww.google.com -d1"))
        assertEquals(listOf("-Qrand", "-d1"), strategy("-Qrand -d1"))
        assertEquals(listOf("-At,r,s", "-d1"), strategy("-At,r,s -d1"))
        assertEquals(listOf("-I10.0.0.1", "-d1"), strategy("-I10.0.0.1 -d1"))
        assertEquals(listOf("-V443", "-d1"), strategy("-V443 -d1"))
        assertEquals(listOf("-x1", "-b16384", "-c512"), strategy("-x1 -b16384 -c512"))
        assertEquals(listOf("-q4+hm", "-o2", "-a1"), strategy("-q4+hm -o2 -a1"))
    }

    @Test
    fun tokeniserHandlesQuotesAndStrayWhitespace() {
        assertEquals(listOf("-d1", "-s1+s"), strategy("   -d1\t\t-s1+s   "))

        // Quotes group whitespace into a single argv entry and vanish from the token itself.
        // -# (--comment) is the flag whose value legitimately carries spaces; -l used to stand here
        // and is blocked now, so it can no longer prove anything about the tokeniser.
        assertEquals(listOf("-#", "a b"), strategy("-# 'a b'"))
        assertEquals(listOf("-#", "a b"), strategy("-# \"a b\""))
        assertEquals(listOf("-nwww.google.com", "-d1"), strategy("-n\"www.google.com\" -d1"))
        assertEquals(listOf("-nwww.google.com", "-d1"), strategy("-n'www.google.com' -d1"))
    }

    /**
     * The load-bearing invariant of the migration, and the only one that cannot be recovered from
     * later: the repaired string has to be the CURRENT catalog text, not merely an equivalent
     * command line. Everything downstream compares text — byCommand picks the preset the picker
     * highlights, labelResForCommand picks the name the UI and the support report print — so a
     * command that runs identically but reads differently silently becomes "custom", and the user
     * who was on a named preset can no longer tell us which one.
     *
     * The old spelling is derived by stripping the timeout token off the current one, which is
     * exactly what those strings were before it was added, so this also asserts the migration is the
     * precise inverse of that edit rather than something that merely looks like it.
     */
    @Test
    fun aSupersededCatalogAutoCommandMigratesBackOntoTheCatalogEntryByteForByte() {
        val autoPresets = ByedpiPresetCatalog.presets
            .filter { p -> words(p.command).any { it.startsWith("-A") } }
        assertTrue("no catalog preset opens an -A group any more — this test covers nothing",
            autoPresets.isNotEmpty())

        for (preset in autoPresets) {
            val current = ByedpiPresetCatalog.byId(preset.id)!!.command
            val superseded = withoutTimeout(current)

            assertTrue(
                "the derivation left a -T in '${preset.id}', so this test asserts nothing",
                timeoutTokens(superseded).isEmpty(),
            )
            // The premise of the whole exercise: as it stands on disk, the old spelling matches no
            // shipped strategy and is therefore already being shown as a custom command.
            assertNull(
                "'${preset.id}' still resolves without its timeout token — nothing to migrate",
                ByedpiPresetCatalog.byCommand(superseded),
            )

            val migrated = ByedpiPresetCatalog.migrateCommand(superseded)
            assertEquals(
                "migrating the superseded spelling of '${preset.id}' must land on the shipped " +
                    "command byte for byte, or the repaired command reads as a custom one",
                current,
                migrated,
            )
            // Equality above is what makes these two hold, and these two are what the UI asks.
            assertEquals(
                "the migrated command no longer resolves to '${preset.id}'",
                preset.id,
                ByedpiPresetCatalog.byCommand(migrated)?.id,
            )
            assertNotNull(
                "the migrated '${preset.id}' has no strategy label, so it prints as custom",
                StrategyTester.labelResForCommand(migrated),
            )
        }
    }

    /**
     * The same invariant against the auto-tuner's own list. Both sources are checked because both
     * feed the same sweep: a migrated command is seeded as candidate #0, and if its text does not
     * match the tuner's entry it is offered under a generic label — the user then picks between two
     * identical-looking strategies with no way to tell which one the engine is about to run.
     */
    @Test
    fun aSupersededTunerAutoStrategyMigratesBackOntoTheTesterEntryByteForByte() {
        val autoStrategies = StrategyTester.STRATEGIES
            .filter { s -> words(s.command).any { it.startsWith("-A") } }
        assertTrue("nothing in the tuner opens an -A group any more — this test covers nothing",
            autoStrategies.isNotEmpty())

        // Not named `strategy`: that is this file's own helper for the sanitised argv tail.
        for (entry in autoStrategies) {
            val superseded = withoutTimeout(entry.command)
            assertTrue(
                "the derivation left a -T in '${entry.command}', so this test asserts nothing",
                timeoutTokens(superseded).isEmpty(),
            )
            assertNull(
                "'$superseded' already labels — nothing to migrate",
                StrategyTester.labelResForCommand(superseded),
            )

            val migrated = ByedpiPresetCatalog.migrateCommand(superseded)
            assertEquals(
                "migrating '$superseded' must land on the tuner's own string byte for byte",
                entry.command,
                migrated,
            )
            // STRATEGIES is distinctBy command, so this is the entry itself, not a look-alike.
            assertEquals(
                "the migrated command lost its label and would be offered as a custom one",
                entry.labelRes,
                StrategyTester.labelResForCommand(migrated),
            )
        }
    }

    /**
     * Migration runs on every load and again at the top of every sweep, so it has to be a fixpoint.
     * Two timeout tokens is not a parse error — getopt is last-wins, so the command still starts and
     * still works — which is precisely why it would never be noticed: the text drifts one token
     * further from the catalog on each pass, and everything that matches by text quietly stops
     * matching.
     */
    @Test
    fun migrationIsIdempotentSoTheTimeoutTokenCanNeverStack() {
        val handWritten = listOf(
            "-At,r,s -s1+s",
            "--auto t,r,s -s1+s",
            "-NAt,r,s -s1+s",
            "--comment \"a b\" -At,r,s",
            "-d1 -s1+s",
            "-t8",
            "",
            "   ",
        )
        for (command in (shippedCommands() + handWritten).distinct()) {
            val once = ByedpiPresetCatalog.migrateCommand(command)
            assertEquals(
                "migrating '$command' a second time moved it again",
                once,
                ByedpiPresetCatalog.migrateCommand(once),
            )
            assertTrue(
                "migrating '$command' left ${timeoutTokens(once)} for getopt to choose between",
                timeoutTokens(once).size <= 1,
            )
        }
    }

    /**
     * The direction that actually costs the user something. params.timeout is GLOBAL, not scoped to
     * a group, so arming it on a command that opens no -A group gives every connection a hard
     * deadline with nothing to fall back to when it fires: extend.c's on_torst has no next group to
     * move to, so a stall that Linux would have ridden out becomes a reset, and — worse — a
     * DETECT_TORST cached against that destination for later connections to start from.
     *
     * Each entry below is one way to look like an -A group without being one.
     */
    @Test
    fun aCommandWithNoAutoGroupIsNeverTouched() {
        val untouched = listOf(
            // Case is the whole difference: -t is --ttl and -a is --udp-fake. Both ride along in
            // most shipped presets, so a case-insensitive match would migrate nearly the catalog.
            "-t8",
            "-a1",
            "-f-1 -t8 -s1+s -a1",
            // --auto-mode is option 'L', a different option that selects a mode and arms no
            // detector at all. getopt_long settles the exact name "--auto" before it considers any
            // abbreviation, so a prefix match here would repair the wrong command.
            "--auto-mode=s",
            "--auto-mode s",
            "-L1",
            "-Lt,r,s",
            // A value-taking long option with nothing after it to take: the "skip the next token"
            // rule must not walk off the end, and a dangling flag is not an -A group.
            "--fake",
            // 'A' inside a value, not a flag: the cluster walk has to stop at 'n' (--fake-sni),
            // which swallows the rest of the token as its hostname.
            "-nwww.Alpha.example",
            "-n www.Alpha.example",
            // …and 'A' inside the value of a value-taking flag one token further on. -# is
            // --comment, the one flag whose value legitimately carries spaces.
            "--comment \"-A is great\"",
            "-#\"-A is great\"",
            // A timeout with no auto group is not a migration candidate either: -T is only ever
            // added to arm an -A group's detectors, never on its own.
            "-T2:2:2:64",
            "-T 2",
            "--timeout=2",
            "--timeout 2",
            "-XT2:2:2:64",
            // Nothing to migrate, and nothing to trim: an untouched command is returned as-is,
            // whitespace and all.
            "",
            "   ",
        )
        for (command in untouched) {
            assertEquals(
                "'$command' opens no -A group and must come back untouched",
                command,
                ByedpiPresetCatalog.migrateCommand(command),
            )
        }
    }

    /**
     * The hand-written half of the positive set. A pasted -A command has exactly the same dead
     * detector as the ones we used to ship, so matching on shape has to reach every spelling getopt
     * would resolve to option 'A' — not just the one the catalog happens to use.
     */
    @Test
    fun everySpellingOfAnAutoGroupGainsTheTimeoutToken() {
        val repaired = listOf(
            "-At,r,s -s1+s",        // the short form, value glued on — what the catalog ships
            "--auto=t,r,s -s1+s",   // long name, inline value
            "--auto t,r,s -s1+s",   // long name, value in the next argv
            "-NAt,r,s -s1+s",       // clustered: -N (no-domain, boolean) and then -A
            "--fake -1 -At,r,s",    // -A behind a long flag that eats the NEXT token as its value
        )
        for (command in repaired) {
            assertEquals(
                "'$command' arms an -A group whose detectors can never fire",
                "${ByedpiPresetCatalog.AUTO_DETECT_TIMEOUT} $command",
                ByedpiPresetCatalog.migrateCommand(command),
            )
        }

        // The repaired string is trimmed even though the input was not: byCommand and
        // labelResForCommand compare against trimmed text, so keeping the padding would defeat the
        // byte-exactness the migration exists to preserve.
        assertEquals(
            "${ByedpiPresetCatalog.AUTO_DETECT_TIMEOUT} -At,r,s -s1+s",
            ByedpiPresetCatalog.migrateCommand("   -At,r,s -s1+s  "),
        )
    }

    /**
     * Why the result is the original string with a token in front rather than a re-joined token
     * list. Re-joining is lossy in exactly one place — a quoted value containing whitespace — and
     * that loss is not cosmetic: "a b" would come back as two argv entries, so --comment would take
     * "a" and "b" would reach getopt as a positional argument.
     */
    @Test
    fun migrationPrependsRatherThanRejoinsSoQuotedValuesSurviveVerbatim() {
        val command = "--comment \"a b\" -At,r,s"
        val migrated = ByedpiPresetCatalog.migrateCommand(command)

        assertEquals("${ByedpiPresetCatalog.AUTO_DETECT_TIMEOUT} $command", migrated)
        // The quotes are still doing their job by the time the engine path re-splits the string.
        assertEquals(
            listOf(ByedpiPresetCatalog.AUTO_DETECT_TIMEOUT, "--comment", "a b", "-At,r,s"),
            strategy(migrated),
        )
    }

    /**
     * The two-sided guard over everything we ship. A shipped -A command that is not already in its
     * current spelling would mean we shipped the inert auto-detect fresh; a shipped non-auto command
     * that changes here would mean the migration is repairing roughly twenty strategies that never
     * needed it. Either way the sweep would be tuning something other than what it later runs.
     */
    @Test
    fun noShippedCommandNeedsMigrating() {
        val shipped = shippedCommands()
        assertTrue("the shipped command list collapsed — this test now covers nothing",
            shipped.size > 20)
        for (command in shipped) {
            assertEquals(
                "shipped command '$command' is not in its own current spelling",
                command,
                ByedpiPresetCatalog.migrateCommand(command),
            )
        }
    }

    /**
     * The spellings a user can already have on disk with the timeouts in place. Each carries an -A
     * group on purpose: without one migrateCommand returns at the first check and the -T detection
     * below is never reached, so the no-op would prove nothing about it.
     */
    @Test
    fun anAutoCommandThatAlreadyCarriesTheTimeoutIsLeftAlone() {
        val current = listOf(
            "-T2:2:2:64 -At,r,s -s1+s",     // what we ship
            "-T 2 -At,r,s -s1+s",           // value in the next argv
            "--timeout=2 -At,r,s -s1+s",    // long name, inline value
            "--timeout 2 -At,r,s -s1+s",    // long name, value in the next argv
            "-XT2:2:2:64 -At,r,s -s1+s",    // clustered behind -X (no-ipv6, boolean)
            "-At,r,s -T2:2:2:64",           // order is irrelevant — getopt reads the whole argv
        )
        for (command in current) {
            assertEquals(
                "'$command' already arms its -A group and must not be given a second -T",
                command,
                ByedpiPresetCatalog.migrateCommand(command),
            )
        }
    }
}
