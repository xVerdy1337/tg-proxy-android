package com.tgwsproxy.vpn

import androidx.annotation.StringRes
import com.tgwsproxy.R

/**
 * Curated, user-facing ByeDPI strategies.
 *
 * Keep these commands in one place: the VPN defaults, the preset picker and the real native
 * auto-tuner must all test and run the same strings. Commands are candidates, not guarantees —
 * the provider's DPI behaviour can change, so the auto-tuner still verifies them end-to-end.
 *
 * Labels and descriptions are string resources so the UI follows the device locale.
 */
enum class ByedpiPresetGroup(@StringRes val titleRes: Int) {
    LIGHT(R.string.preset_group_light),
    BALANCED(R.string.preset_group_balanced),
    AGGRESSIVE(R.string.preset_group_aggressive),
    EXPERIMENTAL(R.string.preset_group_experimental),
    DIAGNOSTIC(R.string.preset_group_diagnostic),
}

data class ByedpiPreset(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val command: String,
    val group: ByedpiPresetGroup,
    val diagnostic: Boolean = false,
)

object ByedpiPresetCatalog {

    /**
     * The order is intentional: cheaper and more common strategies run first during auto-tune.
     * Keep the list deterministic so progress and support reports are reproducible.
     */
    val presets: List<ByedpiPreset> = listOf(
        ByedpiPreset(
            id = DesyncVpnService.PRESET_AUTO,
            labelRes = R.string.preset_auto_label,
            descriptionRes = R.string.preset_auto_desc,
            command = "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = DesyncVpnService.PRESET_SPLIT,
            labelRes = R.string.preset_split_label,
            descriptionRes = R.string.preset_split_desc,
            command = "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
            group = ByedpiPresetGroup.LIGHT,
        ),
        ByedpiPreset(
            id = DesyncVpnService.PRESET_TLSREC,
            labelRes = R.string.preset_tlsrec_label,
            descriptionRes = R.string.preset_tlsrec_desc,
            command = "-d1 -s1+s -r1+s -f-1 -t8 -a1",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "split-minimal",
            labelRes = R.string.preset_split_minimal_label,
            descriptionRes = R.string.preset_split_minimal_desc,
            command = "-s1+s",
            group = ByedpiPresetGroup.LIGHT,
        ),
        ByedpiPreset(
            id = "tlsrec-minimal",
            labelRes = R.string.preset_tlsrec_minimal_label,
            descriptionRes = R.string.preset_tlsrec_minimal_desc,
            command = "-r1+s",
            group = ByedpiPresetGroup.LIGHT,
        ),
        ByedpiPreset(
            id = "disorder-minimal",
            labelRes = R.string.preset_disorder_minimal_label,
            descriptionRes = R.string.preset_disorder_minimal_desc,
            command = "-d1+s",
            group = ByedpiPresetGroup.LIGHT,
        ),
        ByedpiPreset(
            id = "split-triple",
            labelRes = R.string.preset_split_triple_label,
            descriptionRes = R.string.preset_split_triple_desc,
            command = "-s1+s -s3+s -s6+s",
            group = ByedpiPresetGroup.LIGHT,
        ),
        ByedpiPreset(
            id = "disorder-split",
            labelRes = R.string.preset_disorder_split_label,
            descriptionRes = R.string.preset_disorder_split_desc,
            command = "-d1 -s1+s",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "split-tlsrec",
            labelRes = R.string.preset_split_tlsrec_label,
            descriptionRes = R.string.preset_split_tlsrec_desc,
            command = "-d1 -s1+s -r1+s",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "mixed-short",
            labelRes = R.string.preset_mixed_short_label,
            descriptionRes = R.string.preset_mixed_short_desc,
            command = "-d1 -s1+s -d3+s -s6+s",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "mixed-step",
            labelRes = R.string.preset_mixed_step_label,
            descriptionRes = R.string.preset_mixed_step_desc,
            command = "-d1 -s1+s -s3+s -d6+s -s12+s -a1",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "tlsrec-double",
            labelRes = R.string.preset_tlsrec_double_label,
            descriptionRes = R.string.preset_tlsrec_double_desc,
            // -T is what makes the -A group reachable at all, not a tuning knob: params.timeout /
            // ptimeout / to_count_lim / to_bytes_lim are set by case 'T' alone (byedpi/main.c) and
            // default to 0, and against a silent SNI drop none of t/r/s has any other trigger — no
            // RST, no FIN, no redirect, and connect() succeeds. Field 1 becomes TCP_USER_TIMEOUT on
            // the upstream socket (extend.c setup_conn) and is the one that fires when the DPI eats
            // the ClientHello and nothing is ever ACKed. It is coupled to
            // StrategyTester.TLS_TIMEOUT_MS and must stay comfortably under it: detect →
            // handle_err → on_torst → next -A group → replayed ClientHello all has to finish inside
            // the single blocking read the tester gives startHandshake(). At 8s it did not, so both
            // -A presets scored dead in every sweep while quietly recovering in production.
            // Fields 2:3 are the in-band record timer (2s × 2) for a reply that starts and then
            // stalls mid-TLS-record. Field 4 is the release valve, not padding: extend.c lifts the
            // socket timeout and drops the record timer only once recv_count passes to_bytes_lim,
            // so 64 confines the aggressive limit to the pre-first-response window it was added
            // for. Anything large — or 0, which never releases — keeps it armed for the LIFE of
            // every connection that stays under it (upload-heavy POSTs, push channels, small calls
            // on a kept-alive H2 socket), turning an ACK blackout Linux would ride out into either
            // a RST with no retry (sq_buff already freed → can_reconn false) or, earlier, a bogus
            // DETECT_TORST cached against that IP for later connections to start from.
            command = "-T2:2:2:64 -r1+s -s25+s -a1 -At,r,s -s50 -r1+s -s50+s -a1",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "auto-oob",
            labelRes = R.string.preset_auto_oob_label,
            descriptionRes = R.string.preset_auto_oob_desc,
            // -T for the reason spelled out on tlsrec-double: -A's detectors are inert without it,
            // field 1 is bounded by StrategyTester.TLS_TIMEOUT_MS and field 4 keeps the socket
            // timeout off live connections. Keep the value identical to tlsrec-double's.
            command = "-T2:2:2:64 -o1 -a1 -At,r,s -d1",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "disorder-oob",
            labelRes = R.string.preset_disorder_oob_label,
            descriptionRes = R.string.preset_disorder_oob_desc,
            command = "-d6+s -q4+hm -o2 -a1",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "fake-ttl6",
            labelRes = R.string.preset_fake_ttl6_label,
            descriptionRes = R.string.preset_fake_ttl6_desc,
            command = "-f-1 -t6 -s1+s -a1",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "fake-ttl8",
            labelRes = R.string.preset_fake_ttl8_label,
            descriptionRes = R.string.preset_fake_ttl8_desc,
            command = "-f-1 -t8 -s1+s -r1+s -a1",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "long-cascade",
            labelRes = R.string.preset_long_cascade_label,
            descriptionRes = R.string.preset_long_cascade_desc,
            command = "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "oob-disorder",
            labelRes = R.string.preset_oob_disorder_label,
            descriptionRes = R.string.preset_oob_disorder_desc,
            command = "-q4+hm -o2 -a1",
            group = ByedpiPresetGroup.EXPERIMENTAL,
        ),
        ByedpiPreset(
            id = "fake-random-tls",
            labelRes = R.string.preset_fake_random_tls_label,
            descriptionRes = R.string.preset_fake_random_tls_desc,
            command = "-f-1 -t8 -Qrand -s1+s -a1",
            group = ByedpiPresetGroup.EXPERIMENTAL,
        ),
        ByedpiPreset(
            id = "fake-original-tls",
            labelRes = R.string.preset_fake_original_tls_label,
            descriptionRes = R.string.preset_fake_original_tls_desc,
            command = "-f-1 -t8 -Qorig -s1+s -a1",
            group = ByedpiPresetGroup.EXPERIMENTAL,
        ),
        ByedpiPreset(
            id = "fake-google-sni",
            labelRes = R.string.preset_fake_google_sni_label,
            descriptionRes = R.string.preset_fake_google_sni_desc,
            command = "-f-1 -t8 -nwww.google.com -s1+s -a1",
            group = ByedpiPresetGroup.EXPERIMENTAL,
        ),
        ByedpiPreset(
            id = "fake-microsoft-sni",
            labelRes = R.string.preset_fake_microsoft_sni_label,
            descriptionRes = R.string.preset_fake_microsoft_sni_desc,
            command = "-f-1 -t6 -nwww.microsoft.com -d1+s -a1",
            group = ByedpiPresetGroup.EXPERIMENTAL,
        ),
        ByedpiPreset(
            id = DesyncVpnService.PRESET_OFF,
            labelRes = R.string.preset_off_label,
            descriptionRes = R.string.preset_off_desc,
            command = "",
            group = ByedpiPresetGroup.DIAGNOSTIC,
            diagnostic = true,
        ),
    )

    val autotunePresets: List<ByedpiPreset> = presets.filterNot { it.diagnostic }

    /** The timeout token every -A command carries; see the note on the "tlsrec-double" preset. */
    const val AUTO_DETECT_TIMEOUT = "-T2:2:2:64"

    /**
     * Bring a persisted command up to the current spelling.
     *
     * A command that carries an -A group but no -T has a provably inert auto-detect: nothing but
     * case 'T' (byedpi/main.c) sets params.timeout/ptimeout, and without them none of -A's t/r/s
     * detectors has a trigger against a silent SNI drop. Every such string this app ever wrote is
     * one of those, and they cannot be left to age out on their own: auto-tune seeds the sweep with
     * the saved and cached commands, and since every group other than -A behaves exactly as before,
     * such a string still passes and re-elects itself ahead of the fixed catalog entry. Versioning
     * the auto-tune cache key alone does not reach it, because the same string also lives in
     * KEY_BYEDPI_CMD, which is what the service actually launches.
     *
     * Matching on the shape rather than on a table of old literals is deliberate: it also repairs a
     * hand-written -A command, which has exactly the same dead detector, and it cannot drift out of
     * date the way a hard-coded list would. The precision that costs is spelled out in [carriesFlag]
     * — a false positive here is not harmless, since params.timeout is global and a command with no
     * detect group has no second group to fall back to, so a stall becomes a hard reset instead of a
     * retry.
     *
     * The result is the ORIGINAL string with a token prepended, never a re-join: re-joining would
     * rewrite whitespace inside quoted values, and byte-exact output is also what keeps a migrated
     * command matching [byCommand] / StrategyTester.labelResForCommand instead of being relabelled
     * as a custom one.
     *
     * The rewrite is unconditional, but it is not a trap door: a deliberate no-timeout -A is spelled
     * `-T0`, which [carriesFlag] sees and this leaves alone, and which byedpi parses back to the
     * params.timeout = 0 default — i.e. exactly the superseded semantics, expressed on purpose.
     */
    fun migrateCommand(command: String): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return command
        val tokens = DesyncVpnService.shellSplit(trimmed)
        // "auto" must be spelled in full: every shorter prefix is ambiguous with --auto-mode, so
        // getopt_long would reject it rather than resolve it. "timeout" is unique from "ti" on —
        // the other t-names are transparent, tfo, ttl, tlsrec, tlsminor.
        if (!carriesFlag(tokens, 'A', "auto", minPrefix = 4)) return command
        if (carriesFlag(tokens, 'T', "timeout", minPrefix = 2)) return command
        return "$AUTO_DETECT_TIMEOUT $trimmed"
    }

    /**
     * Does [tokens] actually select the option spelled [short] / --[long]?
     *
     * Three things make the naive `startsWith` wrong, and all three are reachable from a
     * hand-written command:
     *  - "--auto" must be matched EXACTLY (or as "--auto="): getopt_long resolves an exact name
     *    before any abbreviation, and a looser prefix also swallows --auto-mode, which is a
     *    different option ('L') that sets no detectors at all.
     *  - shorts cluster, so "-NAt,r,s" really is -N followed by -A. The walk stops at the first
     *    letter that takes a value, because that letter swallows the rest of the token — in
     *    "-nwww.Alpha.example" the 'A' is hostname bytes.
     *  - a token that merely *follows* a value-taking flag is that flag's value, not an option, so
     *    `--comment "-A is great"` must not read as an -A group.
     */
    private fun carriesFlag(
        tokens: List<String>,
        short: Char,
        long: String,
        minPrefix: Int,
    ): Boolean {
        var skipNext = false
        for (t in tokens) {
            if (skipNext) { skipNext = false; continue }
            // Everything past getopt's end-of-options marker is an operand, and byedpi never reads
            // operands (parse_args does not look at optind afterwards), so nothing beyond here can
            // select an option.
            if (t == "--") return false
            if (t.startsWith("--")) {
                val name = t.substring(2).substringBefore('=')
                // getopt_long accepts any UNAMBIGUOUS abbreviation, so match by prefix — but only
                // from the length at which this name stops colliding with its neighbours.
                if (name.length >= minPrefix && long.startsWith(name)) return true
                // A long option consumes the next token only when it takes a value and did not
                // carry it inline. Assuming they all do would let a boolean hide the token after
                // it, which both misses a real group and injects a duplicate timeout.
                //
                // Matched by prefix like the name above, not by exact spelling: getopt_long
                // abbreviates booleans too, so "--no-u" really is --no-udp. Where a prefix is
                // ambiguous with a value-taking long, getopt rejects the whole command before
                // byedpi runs, so either answer here is equally fine.
                skipNext = !t.contains('=') &&
                    DesyncVpnService.BOOLEAN_LONG_OPTIONS.none { it.startsWith(name) }
                continue
            }
            if (!t.startsWith("-") || t.length < 2) continue
            for (idx in 1 until t.length) {
                val c = t[idx]
                if (c == short) return true
                if (c in DesyncVpnService.VALUE_SHORT_LETTERS) {
                    // Takes a value: inline if anything follows it, otherwise the next token.
                    skipNext = idx == t.length - 1
                    break
                }
            }
        }
        return false
    }

    fun byId(id: String): ByedpiPreset? = presets.firstOrNull { it.id == id }

    fun byCommand(command: String): ByedpiPreset? =
        presets.firstOrNull { it.command == command.trim() && it.command.isNotEmpty() }

    fun commandFor(id: String): String =
        byId(id)?.command ?: byId(DesyncVpnService.PRESET_AUTO)?.command.orEmpty()
}
