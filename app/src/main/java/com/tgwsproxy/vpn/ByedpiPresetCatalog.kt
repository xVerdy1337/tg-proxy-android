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
            command = "-r1+s -s25+s -a1 -At,r,s -s50 -r1+s -s50+s -a1",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "auto-oob",
            labelRes = R.string.preset_auto_oob_label,
            descriptionRes = R.string.preset_auto_oob_desc,
            command = "-o1 -a1 -At,r,s -d1",
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

    fun byId(id: String): ByedpiPreset? = presets.firstOrNull { it.id == id }

    fun byCommand(command: String): ByedpiPreset? =
        presets.firstOrNull { it.command == command.trim() && it.command.isNotEmpty() }

    fun commandFor(id: String): String =
        byId(id)?.command ?: byId(DesyncVpnService.PRESET_AUTO)?.command.orEmpty()
}
