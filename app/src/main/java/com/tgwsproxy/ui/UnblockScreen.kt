package com.tgwsproxy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tgwsproxy.R
import com.tgwsproxy.ui.theme.Accent
import com.tgwsproxy.ui.theme.Background
import com.tgwsproxy.ui.theme.Border
import com.tgwsproxy.ui.theme.Destructive
import com.tgwsproxy.ui.theme.GlassBorder
import com.tgwsproxy.ui.theme.GlassShadow
import com.tgwsproxy.ui.theme.GlassSurfaceMuted
import com.tgwsproxy.ui.theme.Mauve
import com.tgwsproxy.ui.theme.OnAccent
import com.tgwsproxy.ui.theme.Primary
import com.tgwsproxy.ui.theme.Signal
import com.tgwsproxy.ui.theme.Success
import com.tgwsproxy.ui.theme.Surface
import com.tgwsproxy.ui.theme.SurfaceVariant
import com.tgwsproxy.ui.theme.TextMuted
import com.tgwsproxy.ui.theme.TextPrimary
import com.tgwsproxy.ui.theme.TextSecondary
import com.tgwsproxy.ui.theme.TgWsProxyTheme
import com.tgwsproxy.ui.theme.Warning
import com.tgwsproxy.vpn.ByedpiPreset
import com.tgwsproxy.vpn.ByedpiPresetCatalog
import com.tgwsproxy.vpn.ByedpiPresetGroup
import com.tgwsproxy.vpn.DesyncVpnService
private val OkGreen = Success

/**
 * Identity of a probed service is its RAW HOSTNAME — never the label we draw next to it.
 * `ServiceProbe.host` and the keys of `AutoTuneUiState.hostOk` come from the ViewModel, whose only
 * context is the Application one, while the labels here resolve against the composition's context;
 * under a per-app language override the two pick different locales, every lookup by translated text
 * missed, and all three rows silently rendered «not checked» over a probe that had actually
 * succeeded. Keep these byte-identical to the hosts in DesyncViewModel.targets().
 */
private const val HOST_YOUTUBE = "www.youtube.com"
private const val HOST_YOUTUBE_VIDEO = "redirector.googlevideo.com"
private const val HOST_INSTAGRAM = "www.instagram.com"

/**
 * Display name for a probed host, resolved from the raw hostname the ViewModel hands over. It has to
 * happen in the composition — see the note above for what a label built in the Application locale
 * costs. Unknown hosts show as-is.
 */
@Composable
private fun serviceLabel(host: String): String = when (host) {
    HOST_YOUTUBE -> stringResource(R.string.service_youtube)
    HOST_YOUTUBE_VIDEO -> stringResource(R.string.service_youtube_video)
    HOST_INSTAGRAM -> stringResource(R.string.service_instagram)
    else -> host
}

/**
 * Inline all «Разблокировка» sections into the caller's LazyColumn — single-screen layout,
 * no separate tab/scroll container. Caller owns spacing + bottom padding.
 *
 * `LazyListScope` is NOT @Composable, so each `item { }` lambda collects the flows it needs
 * via `collectAsState()` itself — we don't hoist state reads to this scope.
 */
fun LazyListScope.unblockSections(
    vm: DesyncViewModel,
    onRunAutoTune: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    item(key = "unblock-hero") {
        val state by vm.vpnState.collectAsState()
        val autoTune by vm.autoTune.collectAsState()
        HeroUnblockCard(
            state = state,
            testing = autoTune.running,
            onEnable = onEnable,
            onDisable = onDisable,
        )
    }

    item(key = "auto-tune") {
        val autoTune by vm.autoTune.collectAsState()
        val state by vm.vpnState.collectAsState()
        AutoTuneCard(
            autoTune = autoTune,
            // Busy spans every state in which the one-instance byedpi engine is claimed, not just
            // isRunning: startup takes it seconds before isRunning flips, and teardown holds it until
            // the native thread is joined. Stopping matters on its own because a stop arriving during
            // startup clears isStarting while isRunning was never set — gating on the other two left
            // the button live for the seconds that window lasts, and a sweep there is refused.
            vpnBusy = state.isRunning || state.isStarting || state.isStopping,
            // Mid-shutdown "turn the VPN off first" asks for something already in flight, right under
            // a dial showing state_stopping — state what is happening instead of demanding an action.
            vpnStopping = state.isStopping,
            onRun = onRunAutoTune,
            onReset = { vm.dismissAutoTune() },
            onEnable = onEnable,
        )
    }

    item(key = "services") {
        val probe by vm.probe.collectAsState()
        val autoTune by vm.autoTune.collectAsState()
        ServicesCard(probe, autoTune)
    }

    item(key = "unblock-live-stats") {
        val state by vm.vpnState.collectAsState()
        AnimatedVisibility(
            visible = state.isRunning,
            enter = fadeIn(tween(220, easing = JevioEaseOut)) + expandVertically(tween(220, easing = JevioEaseOut)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
        ) { LiveStatsCard(state) }
    }

    item(key = "unblock-settings") {
        val settings by vm.settings.collectAsState()
        val probe by vm.probe.collectAsState()
        val excluded by vm.excluded.collectAsState()
        var showExclusions by remember { mutableStateOf(false) }
        if (showExclusions) {
            val installedApps by vm.installedApps.collectAsState()
            ExclusionDialog(
                apps = installedApps,
                excluded = excluded,
                builtIn = vm.builtInExcluded,
                onToggle = { pkg, on -> vm.setExcluded(pkg, on) },
                onClose = { showExclusions = false },
            )
        }
        UnblockSettingsCard(
            settings = settings,
            probe = probe,
            onCheck = { vm.runProbe() },
            onSelectPreset = { vm.setPreset(it) },
            onApplyCmd = { vm.setByedpiCmd(it) },
            presetDefault = vm.defaultCmdForPreset(settings.preset),
            onBlockQuic = { vm.setBlockQuic(it) },
            onAllApps = { vm.setAllApps(it) },
            excludedCount = excluded.size + vm.builtInExcluded.size,
            onOpenExclusions = { vm.loadInstalledApps(); showExclusions = true },
        )
    }
}

@Composable
private fun HeroUnblockCard(
    state: DesyncVpnService.VpnState,
    testing: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    val running = state.isRunning
    val starting = state.isStarting
    val stopping = state.isStopping
    val busy = starting || stopping || testing
    val reduceMotion = reducedMotionEnabled()
    val stateLabelStyle = if (LocalDensity.current.fontScale >= 1.5f) {
        MaterialTheme.typography.titleLarge
    } else {
        MaterialTheme.typography.headlineMedium
    }
    val stateLabel = when {
        testing -> stringResource(R.string.state_tuning)
        stopping -> stringResource(R.string.state_stopping)
        starting -> stringResource(R.string.state_starting)
        running -> stringResource(R.string.state_on)
        else -> stringResource(R.string.state_off)
    }

    JevioGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tab_sites),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.local_bypass),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 2,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        JevioStateDial(
            active = running,
            busy = busy,
            onClick = { if (running) onDisable() else onEnable() },
            accessibilityLabel = stringResource(R.string.a11y_sites_bypass),
            onClickLabel = if (running) stringResource(R.string.a11y_disable_bypass) else stringResource(R.string.a11y_enable_bypass),
            announcedState = when {
                testing -> stringResource(R.string.a11y_tuning_method)
                stopping -> stringResource(R.string.a11y_turning_off)
                starting -> stringResource(R.string.a11y_turning_on)
                running -> stringResource(R.string.state_on)
                else -> stringResource(R.string.state_off)
            },
            icon = Icons.Default.Bolt,
        )
        Spacer(Modifier.height(18.dp))

        Crossfade(
            targetState = stateLabel,
            animationSpec = tween(if (reduceMotion) 0 else 220, easing = JevioEaseOut),
            label = "sitesStateLabel",
            modifier = Modifier.fillMaxWidth(),
        ) { label ->
            Text(
                text = label,
                color = if (running && !busy) OkGreen else TextPrimary,
                style = stateLabelStyle,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                testing -> stringResource(R.string.sites_testing_hint)
                stopping -> stringResource(R.string.sites_stopping_hint)
                starting -> stringResource(R.string.sites_starting_hint)
                running -> stringResource(R.string.sites_running_hint)
                else -> stringResource(R.string.sites_idle_hint)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        state.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Destructive,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }

    }
}

@Composable
private fun AutoTuneCard(
    autoTune: AutoTuneUiState,
    /** VPN running, starting *or* stopping — the byedpi engine is taken throughout, so tuning is off. */
    vpnBusy: Boolean,
    /** Strict subset of [vpnBusy]: the shutdown is already running, so only the label changes. */
    vpnStopping: Boolean,
    onRun: () -> Unit,
    onReset: () -> Unit,
    onEnable: () -> Unit,
) {
    when {
        autoTune.running -> {
            PanelCard {
                Text(stringResource(R.string.auto_tune_title), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                    Text(
                        stringResource(R.string.auto_tune_progress, autoTune.index, autoTune.total),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
                    )
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (autoTune.index.toFloat() / autoTune.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    color = Accent,
                    trackColor = SurfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.auto_tune_keep_open),
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                )
            }
        }

        autoTune.finished && autoTune.foundLabel != null -> {
            PanelCard {
                Text(
                    stringResource(R.string.auto_tune_found, autoTune.foundLabel ?: ""),
                    color = OkGreen, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                )
                DnsSkippedNote(autoTune.unresolvedHosts)
                Spacer(Modifier.height(10.dp))
                PrimaryButton(stringResource(R.string.enable), onClick = onEnable)
                Spacer(Modifier.height(8.dp))
                SecondaryButton(stringResource(R.string.auto_tune_again), onReset, hapticFeedback = true)
            }
        }

        autoTune.finished && autoTune.error != null -> {
            PanelCard {
                Text(autoTune.error, color = Warning, style = MaterialTheme.typography.bodySmall)
                DnsSkippedNote(autoTune.unresolvedHosts)
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    when {
                        vpnStopping -> stringResource(R.string.auto_tune_vpn_stopping)
                        vpnBusy -> stringResource(R.string.turn_off_vpn_first)
                        else -> stringResource(R.string.try_again)
                    },
                    enabled = !vpnBusy,
                    onClick = onRun,
                )
            }
        }

        else -> {
            SecondaryButton(
                label = when {
                    vpnStopping -> stringResource(R.string.auto_tune_vpn_stopping)
                    vpnBusy -> stringResource(R.string.turn_off_vpn_first_long)
                    else -> stringResource(R.string.auto_tune_button)
                },
                onClick = onRun,
                enabled = !vpnBusy,
                hapticFeedback = true,
            )
        }
    }
}

/**
 * Targets the sweep deliberately left untested because this network cannot resolve them. Nothing is
 * drawn when every target resolved, so both finished branches can call it unconditionally.
 */
@Composable
private fun DnsSkippedNote(hosts: List<String>) {
    if (hosts.isEmpty()) return
    // The label lookup is a composable call, so it cannot happen inside joinToString's lambda —
    // that one is not inlined. Collect the names with a plain loop, then join.
    val labels = ArrayList<String>(hosts.size)
    for (host in hosts) labels.add(serviceLabel(host))
    val names = labels.joinToString(", ")
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.auto_tune_dns_skipped, names),
        color = TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean = true,
    hapticFeedback: Boolean = true,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) Accent else SurfaceVariant)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled) {
                if (hapticFeedback) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                onClick()
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        JevioButtonLabel(
            text = label,
            color = if (enabled) OnAccent else TextMuted,
        )
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    hapticFeedback: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) GlassSurfaceMuted else SurfaceVariant)
            .border(
                1.dp,
                Primary.copy(alpha = if (enabled) 0.20f else 0.10f),
                RoundedCornerShape(999.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled,
            ) {
                if (hapticFeedback) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onClick()
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        JevioButtonLabel(text = label, color = if (enabled) TextPrimary else TextMuted)
    }
}

@Composable
private fun ServicesCard(probe: ProbeUiState, autoTune: AutoTuneUiState) {
    val ytLabel = stringResource(R.string.service_youtube)
    val ytvLabel = stringResource(R.string.service_youtube_video)
    val igLabel = stringResource(R.string.service_instagram)
    // Labels are for display only; both lookups key off the hostname (see HOST_* above).
    val yt = probe.results.firstOrNull { it.host == HOST_YOUTUBE }
    val ytv = probe.results.firstOrNull { it.host == HOST_YOUTUBE_VIDEO }
    val ig = probe.results.firstOrNull { it.host == HOST_INSTAGRAM }
    val busy = probe.checking || autoTune.running
    PanelCard {
        ServiceRow(ytLabel, yt, busy, autoTune.hostOk[HOST_YOUTUBE])
        HorizontalDivider(color = Border.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 2.dp))
        ServiceRow(ytvLabel, ytv, busy, autoTune.hostOk[HOST_YOUTUBE_VIDEO])
        HorizontalDivider(color = Border.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 2.dp))
        ServiceRow(igLabel, ig, busy, autoTune.hostOk[HOST_INSTAGRAM])
    }
}

@Composable
private fun ServiceRow(
    name: String,
    result: ServiceProbe?,
    checking: Boolean,
    /**
     * Auto-tune's verdict for this host, or null when the sweep never reached one. A host the
     * network could not resolve is kept OUT of hostOk on purpose, so it falls through to the manual
     * probe below (usually «not checked», grey) instead of borrowing the red «blocked» that belongs
     * to a host we actually tested and lost.
     */
    forced: Boolean?,
) {
    val (dotColor, statusText, working) = when {
        checking -> Triple(SurfaceVariant, stringResource(R.string.probe_checking), false)
        forced == true -> Triple(OkGreen, stringResource(R.string.probe_works), true)
        forced == false -> Triple(Destructive, stringResource(R.string.probe_blocked), false)
        result == null -> Triple(SurfaceVariant, stringResource(R.string.probe_not_checked), false)
        result.anyPass -> Triple(OkGreen, stringResource(R.string.probe_works), true)
        result.plain == com.tgwsproxy.net.HelloProbe.Outcome.BLOCKED -> Triple(Destructive, stringResource(R.string.probe_blocked), false)
        else -> Triple(Warning, stringResource(R.string.probe_failed), false)
    }
    val animatedDot by animateColorAsState(targetValue = dotColor, animationSpec = tween(250), label = "serviceDot")
    val statusColor = when {
        working -> OkGreen
        dotColor == Destructive -> Destructive
        dotColor == Warning -> Warning
        else -> TextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(animatedDot.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = TextSecondary
                )
            } else if (working) {
                Icon(Icons.Default.Check, null, tint = OkGreen, modifier = Modifier.size(14.dp))
            } else {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(animatedDot))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        Text(
            statusText,
            color = statusColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun LiveStatsCard(state: DesyncVpnService.VpnState) {
    val context = LocalContext.current
    PanelCard {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 300.dp) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        StatItem(stringResource(R.string.stat_conns), "${state.activeTcp}", Modifier.weight(1f))
                        StatItem(stringResource(R.string.stat_sent), formatBytesShort(context, state.bytesUp), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatItem(stringResource(R.string.stat_received), formatBytesShort(context, state.bytesDown), Modifier.weight(1f))
                        StatItem(stringResource(R.string.stat_connected), "${state.connOk}", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatItem(stringResource(R.string.stat_failed), "${state.connFail}", Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        StatItem(stringResource(R.string.stat_conns), "${state.activeTcp}", Modifier.weight(1f))
                        StatItem(stringResource(R.string.stat_sent), formatBytesShort(context, state.bytesUp), Modifier.weight(1f))
                        StatItem(stringResource(R.string.stat_received), formatBytesShort(context, state.bytesDown), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatItem(stringResource(R.string.stat_connected), "${state.connOk}", Modifier.weight(1f))
                        StatItem(stringResource(R.string.stat_failed), "${state.connFail}", Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        val err = state.error
        if (!err.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.diagnostics), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(2.dp))
            Text(err, color = Destructive, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // tnum = tabular figures: live stats (connections/bytes) update every second; proportional
        // digits would change width each tick and jitter the centered layout.
        Text(
            value,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Human description of what each preset actually does — shown under the chips. */
@Composable
private fun presetDescription(preset: String, command: String): String {
    val selected = if (command.isBlank()) {
        ByedpiPresetCatalog.byId(preset)
    } else {
        ByedpiPresetCatalog.byCommand(command)
    }
    return selected?.let { stringResource(it.descriptionRes) }
        ?: stringResource(R.string.custom_byedpi_cmd)
}

@Composable
private fun PresetCard(
    current: String,
    activeLabel: String?,
    command: String,
    onSelect: (String) -> Unit,
) {
    var catalogExpanded by remember { mutableStateOf(false) }
    var expandedPresetId by remember { mutableStateOf<String?>(null) }
    val coreIds = setOf(
        DesyncVpnService.PRESET_AUTO,
        DesyncVpnService.PRESET_TLSREC,
        DesyncVpnService.PRESET_SPLIT,
    )
    val extraPresets = ByedpiPresetCatalog.presets.filterNot {
        it.diagnostic || it.id in coreIds
    }

    PanelCard {
        Text(stringResource(R.string.bypass_method), color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.bypass_method_hint),
            color = TextSecondary, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip(stringResource(R.string.auto), DesyncVpnService.PRESET_AUTO, current, Modifier.weight(1f), onSelect)
            PresetChip("TLS-record", DesyncVpnService.PRESET_TLSREC, current, Modifier.weight(1f), onSelect)
            PresetChip("SNI split", DesyncVpnService.PRESET_SPLIT, current, Modifier.weight(1f), onSelect)
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            if (catalogExpanded) stringResource(R.string.hide_catalog) else stringResource(R.string.more_configs, extraPresets.size),
            onClick = { catalogExpanded = !catalogExpanded },
        )

        if (catalogExpanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.byedpi_catalog),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.byedpi_catalog_hint),
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            ByedpiPresetGroup.values().filterNot { it == ByedpiPresetGroup.DIAGNOSTIC }.forEach { group ->
                val groupPresets = extraPresets.filter { it.group == group }
                if (groupPresets.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(group.titleRes), color = Accent, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    groupPresets.forEach { preset ->
                        StrategyPresetRow(
                            preset = preset,
                            selected = command.trim() == preset.command ||
                                (command.isBlank() && current == preset.id),
                            expanded = expandedPresetId == preset.id,
                            onClick = {
                                onSelect(preset.id)
                                expandedPresetId = if (expandedPresetId == preset.id) null else preset.id
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Accent.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Info, null, tint = Accent, modifier = Modifier.size(16.dp))
            Text(
                presetDescription(current, command),
                color = TextSecondary, style = MaterialTheme.typography.labelSmall
            )
        }
        if (activeLabel != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.active_strategy, activeLabel),
                color = OkGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.restart_vpn_after_method),
            color = TextSecondary, style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun StrategyPresetRow(
    preset: ByedpiPreset,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val reduceMotion = reducedMotionEnabled()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.16f) else SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (selected) Icons.Default.Check else Icons.Default.Tune,
                contentDescription = if (selected) stringResource(R.string.selected) else null,
                tint = if (selected) Accent else TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(preset.labelRes),
                color = TextPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) stringResource(R.string.hide_description) else stringResource(R.string.show_description),
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(if (reduceMotion) 0 else 220, easing = JevioEaseOut)) +
                expandVertically(tween(if (reduceMotion) 0 else 220, easing = JevioEaseOut)),
            exit = fadeOut(tween(if (reduceMotion) 0 else 140)) +
                shrinkVertically(tween(if (reduceMotion) 0 else 140)),
        ) {
            Text(
                text = stringResource(preset.descriptionRes),
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 28.dp, top = 8.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    value: String,
    current: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val selected = current == value
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent else SurfaceVariant)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current) { onSelect(value) }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) OnAccent else TextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Все вторичные настройки разблокировки — свёрнуты под одной кнопкой «Настройки».
 * На главном экране остаётся статус, big CTA, сервисы и автоподбор.
 */
@Composable
private fun UnblockSettingsCard(
    settings: DesyncSettings,
    probe: ProbeUiState,
    onCheck: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onApplyCmd: (String) -> Unit,
    presetDefault: String,
    onBlockQuic: (Boolean) -> Unit,
    onAllApps: (Boolean) -> Unit,
    excludedCount: Int,
    onOpenExclusions: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(14.dp))
                .clickable { open = !open }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Tune, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings), color = TextPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.unblock_settings_subtitle),
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                )
            }
            Icon(
                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                null, tint = TextSecondary, modifier = Modifier.size(22.dp)
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(220, easing = JevioEaseOut)) + expandVertically(tween(220, easing = JevioEaseOut)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PresetCard(
                    current = settings.preset,
                    activeLabel = if (settings.byedpiCmd.isBlank()) null
                    else com.tgwsproxy.net.StrategyTester.labelForCommand(
                        LocalContext.current,
                        settings.byedpiCmd,
                    ) ?: stringResource(R.string.custom_command),
                    command = settings.byedpiCmd,
                    onSelect = onSelectPreset,
                )
                ToggleCard(
                    icon = Icons.Default.Bolt,
                    title = stringResource(R.string.block_quic),
                    subtitle = stringResource(R.string.block_quic_subtitle),
                    checked = settings.blockQuic,
                    onChange = onBlockQuic,
                )
                DiagnosticPresetCard(settings.preset, onSelectPreset)
                ProbeCard(probe, onCheck)
                ByedpiCommandCard(
                    command = settings.byedpiCmd,
                    presetDefault = presetDefault,
                    onApply = onApplyCmd,
                )
                ToggleCard(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.all_apps),
                    subtitle = if (settings.allApps)
                        stringResource(R.string.all_apps_on_subtitle)
                    else
                        stringResource(R.string.all_apps_off_subtitle),
                    checked = settings.allApps,
                    onChange = onAllApps
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface)
                        .border(1.dp, Border, RoundedCornerShape(14.dp))
                        .clickable { onOpenExclusions() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Block, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.exclusions_title), color = TextPrimary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.exclusions_count, excludedCount),
                            color = TextSecondary, style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticPresetCard(
    current: String,
    onSelect: (String) -> Unit,
) {
    PanelCard {
        Text(stringResource(R.string.pipe_diagnostics), color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.pipe_diagnostics_desc),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        PresetChip(
            stringResource(R.string.check_without_bypass),
            DesyncVpnService.PRESET_OFF,
            current,
            Modifier.fillMaxWidth(),
            onSelect,
        )
    }
}

@Composable
private fun ProbeCard(probe: ProbeUiState, onCheck: () -> Unit) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.manual_method_check), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.manual_method_check_desc),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (probe.checking) SurfaceVariant else Accent)
                    .clickable(enabled = !probe.checking) { onCheck() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (probe.checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TextSecondary
                    )
                } else {
                    Text(stringResource(R.string.check), color = OnAccent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (probe.results.isNotEmpty() && !probe.checking) {
            Spacer(Modifier.height(12.dp))
            for (r in probe.results) {
                ProbeResultRow(r)
                Spacer(Modifier.height(6.dp))
            }
            val anyWorks = probe.results.any { it.anyPass }
            Spacer(Modifier.height(2.dp))
            Text(
                if (anyWorks)
                    stringResource(R.string.probe_simple_ok)
                else
                    stringResource(R.string.probe_simple_fail),
                color = if (anyWorks) OkGreen else Warning,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ProbeResultRow(r: ServiceProbe) {
    val name = serviceLabel(r.host)
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = name,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MethodPill(stringResource(R.string.method_plain), r.plain)
            MethodPill("A", r.tlsrec)
            MethodPill("B", r.split)
        }
    }
}

@Composable
private fun MethodPill(label: String, outcome: com.tgwsproxy.net.HelloProbe.Outcome?) {
    val color = when (outcome) {
        com.tgwsproxy.net.HelloProbe.Outcome.PASS -> OkGreen
        com.tgwsproxy.net.HelloProbe.Outcome.BLOCKED -> Destructive
        com.tgwsproxy.net.HelloProbe.Outcome.ERROR -> Warning
        null -> SurfaceVariant
    }
    Text(
        label,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun ByedpiCommandCard(
    command: String,
    presetDefault: String,
    onApply: (String) -> Unit,
) {
    var text by remember(command) { mutableStateOf(command) }
    PanelCard {
        Text(stringResource(R.string.byedpi_cmd_title), color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        val activeCmd = command.ifBlank {
            presetDefault.ifBlank { stringResource(R.string.no_desync) }
        }
        Text(
            stringResource(R.string.byedpi_cmd_desc, activeCmd),
            color = TextSecondary, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(presetDefault.ifBlank { "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1" }, color = TextMuted) },
            singleLine = false,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = SurfaceVariant,
                unfocusedContainerColor = SurfaceVariant,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent,
                focusedIndicatorColor = Accent,
                unfocusedIndicatorColor = Border,
            )
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent)
                    .clickable {
                        // Settle the field on what will actually be stored. setByedpiCmd migrates
                        // a superseded -A spelling, and if the result equals what is already saved
                        // the StateFlow conflates, [command] never changes, remember(command) is
                        // never re-keyed — so the box would keep showing the edit while the line
                        // above it showed the real command, and the tap would look dead.
                        val applied = ByedpiPresetCatalog.migrateCommand(text.trim())
                        text = applied
                        onApply(applied)
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.apply), color = OnAccent, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceVariant)
                    .clickable { text = ""; onApply("") }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.reset), color = TextPrimary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.byedpi_cmd_help),
            color = TextSecondary, style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Mauve, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OnAccent,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = SurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun ExclusionDialog(
    apps: List<AppInfo>,
    excluded: Set<String>,
    builtIn: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.exclusions_title), color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.exclusions_dialog_desc),
                color = TextSecondary, style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_app), color = TextMuted) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceVariant,
                    unfocusedContainerColor = SurfaceVariant,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Accent,
                    focusedIndicatorColor = Accent,
                    unfocusedIndicatorColor = Border,
                )
            )
            Spacer(Modifier.height(10.dp))
            if (apps.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                    Text(stringResource(R.string.loading_apps), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val filtered = apps.filter {
                    query.isBlank() || it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
                }
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(filtered, key = { it.pkg }) { app ->
                        val checked = app.builtIn || excluded.contains(app.pkg)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !app.builtIn) { onToggle(app.pkg, !checked) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = if (app.builtIn) null else { v -> onToggle(app.pkg, v) },
                                enabled = !app.builtIn,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (app.builtIn) stringResource(R.string.built_in_exclusion) else app.pkg,
                                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(stringResource(R.string.done), hapticFeedback = false, onClick = onClose)
        }
    }
}

/** Reusable card surface — renamed from the previous local `Card` to avoid shadowing material3.Card. */
@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    JevioGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(16.dp),
        content = content,
    )
}

private fun formatBytesShort(context: android.content.Context, b: Long): String {
    if (b < 1024) return context.getString(R.string.unit_b, b)
    val kb = b / 1024.0
    if (kb < 1024) return context.getString(R.string.unit_kb_short, kb)
    val mb = kb / 1024.0
    if (mb < 1024) return context.getString(R.string.unit_mb, mb)
    return context.getString(R.string.unit_gb, mb / 1024.0)
}

@Preview(
    name = "Сайты — выключено",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
    backgroundColor = 0xFFF7F4EF,
)
@Composable
private fun UnblockStoppedPreview() {
    UnblockPreviewContent(
        state = DesyncVpnService.VpnState(),
        autoTune = AutoTuneUiState(),
    )
}

@Preview(
    name = "Сайты — работает",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
    backgroundColor = 0xFFF7F4EF,
)
@Composable
private fun UnblockRunningPreview() {
    UnblockPreviewContent(
        state = DesyncVpnService.VpnState(
            isRunning = true,
            preset = DesyncVpnService.PRESET_AUTO,
            activeTcp = 4,
            bytesUp = 1_250_000,
            bytesDown = 24_800_000,
            connOk = 18,
            connFail = 1,
            startedAt = System.currentTimeMillis() - 12 * 60 * 1000,
        ),
        autoTune = AutoTuneUiState(
            finished = true,
            foundLabel = stringResource(R.string.auto),
            hostOk = mapOf(
                HOST_YOUTUBE to true,
                HOST_YOUTUBE_VIDEO to true,
                HOST_INSTAGRAM to true,
            ),
        ),
    )
}

@Composable
private fun UnblockPreviewContent(
    state: DesyncVpnService.VpnState,
    autoTune: AutoTuneUiState,
) {
    TgWsProxyTheme {
        JevioBackground(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    HeroUnblockCard(
                        state = state,
                        testing = autoTune.running,
                        onEnable = {},
                        onDisable = {},
                    )
                }
                item {
                    ServicesCard(
                        probe = ProbeUiState(),
                        autoTune = autoTune,
                    )
                }
                if (state.isRunning) {
                    item { LiveStatsCard(state) }
                } else {
                    item {
                        AutoTuneCard(
                            autoTune = autoTune,
                            // Mirrors the real gate (isRunning is already false in this branch) so
                            // the starting-state preview shows the long disabled label it actually
                            // renders — that is the one at risk of overflowing at 320dp / 1.35×.
                            vpnBusy = state.isStarting || state.isStopping,
                            vpnStopping = state.isStopping,
                            onRun = {},
                            onReset = {},
                            onEnable = {},
                        )
                    }
                }
            }
        }
    }
}

@Preview(
    name = "QA — Сайты — 320dp — шрифт 1.35×",
    widthDp = 320,
    heightDp = 900,
    fontScale = 1.35f,
    showBackground = true,
    backgroundColor = 0xFFECE9E3,
)
@Composable
private fun UnblockNarrowLargeTextPreview() {
    UnblockPreviewContent(
        state = DesyncVpnService.VpnState(
            isStarting = true,
            preset = DesyncVpnService.PRESET_AUTO,
        ),
        autoTune = AutoTuneUiState(),
    )
}

@Preview(
    name = "QA — метод обхода — 320dp — шрифт 1.5×",
    widthDp = 320,
    heightDp = 420,
    fontScale = 1.5f,
    showBackground = true,
    backgroundColor = 0xFFF7F4EF,
)
@Composable
private fun StrategyPresetNarrowLargeTextPreview() {
    val preset = ByedpiPresetCatalog.presets.first { it.id == "fake-ttl8" }
    TgWsProxyTheme {
        JevioBackground(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp)) {
                PanelCard {
                    StrategyPresetRow(
                        preset = preset,
                        selected = true,
                        expanded = true,
                        onClick = {},
                    )
                }
            }
        }
    }
}

/** The unblock part of [FullMainScreenPreview], kept here so it uses the real private components. */
@Composable
internal fun FullUnblockPreviewContent() {
    val state = DesyncVpnService.VpnState()
    val autoTune = AutoTuneUiState()
    val settings = DesyncSettings()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HeroUnblockCard(
            state = state,
            testing = autoTune.running,
            onEnable = {},
            onDisable = {},
        )
        ServicesCard(probe = ProbeUiState(), autoTune = autoTune)
        AutoTuneCard(
            autoTune = autoTune,
            vpnBusy = false,
            vpnStopping = false,
            onRun = {},
            onReset = {},
            onEnable = {},
        )
        UnblockSettingsCard(
            settings = settings,
            probe = ProbeUiState(),
            onCheck = {},
            onSelectPreset = {},
            onApplyCmd = {},
            presetDefault = ByedpiPresetCatalog.commandFor(settings.preset),
            onBlockQuic = {},
            onAllApps = {},
            excludedCount = 0,
            onOpenExclusions = {},
        )
    }
}
