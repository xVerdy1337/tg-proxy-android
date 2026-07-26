package com.tgwsproxy.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** The strategy tail — what actually reaches getopt after our pinned listen endpoint. */
    private fun strategy(command: String): List<String> = args(command).drop(PINNED.size)

    private fun words(command: String): List<String> =
        command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

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
        // -r tlsrec, -f fake, -t fake TTL, -o oob, -q disoob, -a udp-fake, -A auto, plus the
        // "+s" suffix that anchors an offset to the SNI.
        val command = "-d1 -s1+s -r1+s -f-1 -t8 -o1 -q4+hm -a1 -At,r,s -s50+s"
        val out = strategy(command)

        assertEquals(words(command), out)
        for (flag in listOf("-d", "-s", "-r", "-f", "-t", "-o", "-q", "-a", "-A")) {
            assertTrue("$flag was eaten by the listen-flag filter", out.any { it.startsWith(flag) })
        }
        assertEquals(
            "the +s SNI offsets must reach the engine untouched",
            listOf("-s1+s", "-r1+s", "-s50+s"),
            out.filter { it.endsWith("+s") },
        )
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
}
