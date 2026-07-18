package com.tgwsproxy.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings
import com.tgwsproxy.ui.theme.*
import com.tgwsproxy.vpn.DesyncVpnService
import com.tgwsproxy.vpn.ByedpiPresetCatalog
import com.tgwsproxy.vpn.ByedpiPreset
import com.tgwsproxy.vpn.ByedpiPresetGroup
import java.util.Locale

@Composable
fun UnblockScreen(
    vm: DesyncViewModel,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    val state by vm.vpnState.collectAsState()
    val settings by vm.settings.collectAsState()
    val probe by vm.probe.collectAsState()
    val autoTune by vm.autoTune.collectAsState()
    val running = state.isRunning
    val installedApps by vm.installedApps.collectAsState()
    val excluded by vm.excluded.collectAsState()
    var advancedOpen by remember { mutableStateOf(false) }
    var showExclusions by remember { mutableStateOf(false) }

    if (showExclusions) {
        ExclusionDialog(
            apps = installedApps,
            excluded = excluded,
            builtIn = vm.builtInExcluded,
            onToggle = { pkg, on -> vm.setExcluded(pkg, on) },
            onClose = { showExclusions = false },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item { HeroUnblockCard(state, autoTune.running, onEnable, onDisable) }

        item {
            AutoTuneCard(
                autoTune = autoTune,
                vpnRunning = running,
                onRun = { vm.runAutoTune() },
                onReset = { vm.dismissAutoTune() },
                onEnable = onEnable,
            )
        }

        item { ServicesRow(probe, autoTune) }

        item {
            // Asymmetric enter/exit (Emil): reveal ~220ms, dismiss faster ~140ms; no jarring pop-in.
            AnimatedVisibility(
                visible = running,
                enter = fadeIn(tween(220, easing = EmilEaseOut)) + expandVertically(tween(220, easing = EmilEaseOut)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
            ) { LiveStatsCard(state) }
        }

        item { Text("Метод обхода", style = MaterialTheme.typography.labelLarge, color = TextSecondary) }

        item {
            PresetCard(
                current = settings.preset,
                running = running,
                activeLabel = if (settings.byedpiCmd.isBlank()) null
                    else com.tgwsproxy.net.StrategyTester.labelForCommand(settings.byedpiCmd) ?: "своя команда",
                command = settings.byedpiCmd,
                onSelect = { vm.setPreset(it) },
            )
        }

        item {
            ToggleCard(
                icon = Icons.Default.Bolt,
                title = "Блокировать QUIC",
                subtitle = "Ускоряет обход для YouTube: заставляет приложение использовать обычный TLS вместо QUIC",
                checked = settings.blockQuic,
                onChange = { vm.setBlockQuic(it) }
            )
        }

        item {
            AdvancedSection(
                expanded = advancedOpen,
                onToggle = { advancedOpen = !advancedOpen },
                probe = probe,
                onCheck = { vm.runProbe() },
                command = settings.byedpiCmd,
                presetDefault = vm.defaultCmdForPreset(settings.preset),
                onApplyCmd = { vm.setByedpiCmd(it) },
                preset = settings.preset,
                onSelectPreset = { vm.setPreset(it) },
                allApps = settings.allApps,
                onAllApps = { vm.setAllApps(it) },
                excludedCount = excluded.size + vm.builtInExcluded.size,
                onOpenExclusions = { vm.loadInstalledApps(); showExclusions = true },
            )
        }

        item {
            AnimatedVisibility(
                visible = running,
                enter = fadeIn(tween(220, easing = EmilEaseOut)) + expandVertically(tween(220, easing = EmilEaseOut)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
            ) { RestartHintCard() }
        }

        item { DisclaimerCard() }

        item { Spacer(Modifier.height(24.dp)) }
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
    val accent = when {
        testing -> Accent
        running -> Accent
        starting -> Accent
        else -> TextMuted
    }

    // Gentle breathing pulse on the status badge while the VPN is active.
    // Respect reduced-motion (Emil / a11y): keep the opacity level, drop the movement.
    val reduce = reducedMotionEnabled()
    val pulse = rememberInfiniteTransition(label = "hero-pulse")
    val animatedPulse by pulse.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "hero-pulse-alpha"
    )
    val pulseAlpha = if (reduce) 0.20f else animatedPulse
    val badgeAlpha = if (running || starting || testing) pulseAlpha else 0.15f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    if (running || starting || testing) listOf(Color(0xFF2B2540), Surface) else listOf(Surface, Surface)
                )
            )
            .border(
                1.dp,
                if (running || starting || testing) Accent.copy(alpha = 0.45f) else Border,
                RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = badgeAlpha)),
            contentAlignment = Alignment.Center
        ) {
            when {
                testing -> CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = Accent,
                )
                starting -> CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = Accent,
                )
                running -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(28.dp),
                )
                else -> Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = accent,
                    // Optical centering: play triangle's visual weight sits left of geometric center.
                    modifier = Modifier.size(28.dp).offset(x = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Разблокировка YouTube и Instagram",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        when {
            testing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                Text("Тестирование методов обхода…", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            running -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(OkGreen).alpha(pulseAlpha * 3.4f))
                Text(
                    "Включено — обходим блокировку провайдера",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            starting -> Text(
                "Запуск… поднимаем обход (SOCKS + VPN)",
                style = MaterialTheme.typography.bodyMedium,
                color = Accent,
            )
            else -> Text(
                "Локальная обработка трафика без внешнего VPN-сервера",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Destructive,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(16.dp))
        BigToggleButton(
            running = running,
            testing = testing,
            starting = starting,
            onClick = {
                when {
                    starting -> Unit
                    running -> onDisable()
                    else -> onEnable()
                }
            },
        )
    }
}

@Composable
private fun BigToggleButton(running: Boolean, starting: Boolean, testing: Boolean, onClick: () -> Unit) {
    val busy = starting || testing
    val bg: Brush = when {
        busy -> SolidColor(Accent.copy(alpha = 0.55f))
        running -> SolidColor(Destructive)
        else -> AccentGradient
    }
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !busy) 0.96f else 1f, label = "toggleScale")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .then(
                if (busy) Modifier
                else Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            )
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Background,
                )
                Text(if (testing) "Тестирование методов…" else "Запуск…", color = Background, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        } else {
            Text(
                if (running) "Выключить" else "Включить разблокировку",
                color = Background,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}

private val OkGreen = Color(0xFF5BBF7B)

/**
 * Strong ease-out curve (Emil Kowalski / animations.dev). The built-in CSS/Compose easings
 * are too weak to feel intentional; this is the design-eng standard UI ease-out.
 */
private val EmilEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * Reduced-motion (accessibility): true when the OS animator duration scale is 0
 * (Developer options / a11y "remove animations"). We keep opacity/level cues but drop
 * continuous movement, per the design-eng reduced-motion guidance.
 */
@Composable
private fun reducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
}

/**
 * Scale-on-press feedback ("make interfaces feel better" skill): a subtle, interruptible
 * scale(0.96) while pressed. animateFloatAsState retargets mid-press, so releasing early
 * springs back smoothly. Never go below 0.95 — it starts to feel exaggerated.
 */
@Composable
private fun rememberPressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    // Snappy, custom-curve press feedback (Emil: ~140ms, strong ease-out, interruptible).
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 140, easing = EmilEaseOut),
        label = "pressScale"
    )
    return scale
}

@Composable
private fun AutoTuneCard(
    autoTune: AutoTuneUiState,
    vpnRunning: Boolean,
    onRun: () -> Unit,
    onReset: () -> Unit,
    onEnable: () -> Unit,
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoFixHigh, null, tint = Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Автоподбор метода", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    "Сами переберём ${com.tgwsproxy.net.StrategyTester.STRATEGIES.size} методов и оставим рабочий",
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            autoTune.running -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                    Text(
                        "Проверяю ${autoTune.index}/${autoTune.total}",
                        color = TextPrimary,
                        // tabular figures so the "3/10" counter keeps constant width as it ticks.
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Пожалуйста, не закрывайте вкладку во время проверки.",
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                )
            }

            autoTune.finished && autoTune.foundLabel != null -> {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Check, null, tint = OkGreen, modifier = Modifier.size(18.dp))
                    Text(
                        "Готово: «${autoTune.foundLabel}» подобран и сохранён. Нажмите «Включить» — и готово.",
                        color = OkGreen, style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Включить с этим методом", onClick = onEnable)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Подобрать заново", onReset)
            }

            autoTune.finished && autoTune.error != null -> {
                Text(autoTune.error, color = Warning, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                PrimaryButton(if (vpnRunning) "Сначала выключи VPN" else "Попробовать снова", enabled = !vpnRunning, onClick = onRun)
            }

            else -> {
                if (vpnRunning) {
                    Text(
                        "Подбор работает только при выключенном VPN — он монопольно использует движок обхода.",
                        color = TextSecondary, style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(10.dp))
                }
                PrimaryButton(
                    if (vpnRunning) "Сначала выключи VPN" else "Подобрать автоматически",
                    enabled = !vpnRunning,
                    onClick = onRun
                )
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) AccentGradient else SolidColor(SurfaceVariant))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) Background else TextMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariant)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ServicesRow(probe: ProbeUiState, autoTune: AutoTuneUiState) {
    val yt = probe.results.firstOrNull { it.display == "YouTube" }
    val ytv = probe.results.firstOrNull { it.display == "YouTube (видео)" }
    val ig = probe.results.firstOrNull { it.display == "Instagram" }
    val busy = probe.checking || autoTune.running
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ServiceChip("YouTube", yt, busy, autoTune.hostOk["YouTube"], Modifier.weight(1f))
        ServiceChip("Видео", ytv, busy, autoTune.hostOk["YouTube (видео)"], Modifier.weight(1f))
        ServiceChip("Instagram", ig, busy, autoTune.hostOk["Instagram"], Modifier.weight(1f))
    }
}

@Composable
private fun ServiceChip(
    name: String,
    result: ServiceProbe?,
    checking: Boolean,
    forced: Boolean?,
    modifier: Modifier = Modifier,
) {
    // Prefer the real auto-tune result (TLS handshake through byedpi) over the weak Kotlin probe.
    val (dotColor, statusText, working) = when {
        checking -> Triple(SurfaceVariant, "проверка…", false)
        forced == true -> Triple(OkGreen, "работает", true)
        forced == false -> Triple(Destructive, "заблокировано", false)
        result == null -> Triple(SurfaceVariant, "не проверено", false)
        result.anyPass -> Triple(OkGreen, "работает", true)
        result.plain == com.tgwsproxy.net.HelloProbe.Outcome.BLOCKED -> Triple(Destructive, "заблокировано", false)
        else -> Triple(Warning, "не удалось", false)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = 0.22f)),
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
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            }
        }
        Column {
            Text(name, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(statusText, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ProbeCard(probe: ProbeUiState, onCheck: () -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Ручная проверка методов", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Быстрая прямая проба split/tlsrec (без FAKE). Для полного подбора используй «Автоподбор» выше.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
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
                    Text("Проверить", color = Background, fontWeight = FontWeight.SemiBold)
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
                    "Нашёлся рабочий простой метод. Но «Автоподбор» надёжнее — он проверяет и FAKE/TTL."
                else
                    "Простая проба не пробила — это не значит, что не работают каскадные методы. Запусти «Автоподбор» выше.",
                color = if (anyWorks) OkGreen else Warning,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ProbeResultRow(r: ServiceProbe) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(r.display, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MethodPill("Без обхода", r.plain)
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
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun LiveStatsCard(state: DesyncVpnService.VpnState) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatItem("Соединения", "${state.activeTcp}")
            StatItem("Отправлено", formatBytesShort(state.bytesUp))
            StatItem("Получено", formatBytesShort(state.bytesDown))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatItem("Подключено", "${state.connOk}")
            StatItem("Не дошло", "${state.connFail}")
        }
        val err = state.error
        if (!err.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("Диагностика", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(2.dp))
            Text(err, color = Destructive, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // tnum = tabular figures: live stats (connections/bytes) update every second; proportional
        // digits would change width each tick and jitter the centered layout.
        Text(
            value,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            // tnum lives on TextStyle, not as a Text() argument — carry it via style.
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
        )
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

/** Human description of what each preset actually does — shown under the chips. */
private fun presetDescription(preset: String, command: String): String {
    val selected = if (command.isBlank()) {
        ByedpiPresetCatalog.byId(preset)
    } else {
        ByedpiPresetCatalog.byCommand(command)
    }
    return selected?.description
        ?: "Своя команда byedpi. Проверь её на своей сети перед постоянным использованием."
}

@Composable
private fun PresetCard(
    current: String,
    running: Boolean,
    activeLabel: String?,
    command: String,
    onSelect: (String) -> Unit,
) {
    var catalogExpanded by remember { mutableStateOf(false) }
    val coreIds = setOf(
        DesyncVpnService.PRESET_AUTO,
        DesyncVpnService.PRESET_TLSREC,
        DesyncVpnService.PRESET_SPLIT,
    )
    val extraPresets = ByedpiPresetCatalog.presets.filterNot {
        it.diagnostic || it.id in coreIds
    }

    Card {
        Text("Метод обхода", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Если что-то не открывается — переключите метод или нажмите «Автоподбор».",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip("Авто", DesyncVpnService.PRESET_AUTO, current, Modifier.weight(1f), onSelect)
            PresetChip("Метод A", DesyncVpnService.PRESET_TLSREC, current, Modifier.weight(1f), onSelect)
            PresetChip("Метод B", DesyncVpnService.PRESET_SPLIT, current, Modifier.weight(1f), onSelect)
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            if (catalogExpanded) "Скрыть каталог" else "Ещё конфиги (${extraPresets.size})",
            onClick = { catalogExpanded = !catalogExpanded },
        )

        if (catalogExpanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Каталог ByeDPI",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Все варианты сначала проверяются автоподбором. Выбранный конфиг сохранится после перезапуска VPN.",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            ByedpiPresetGroup.values().filterNot { it == ByedpiPresetGroup.DIAGNOSTIC }.forEach { group ->
                val groupPresets = extraPresets.filter { it.group == group }
                if (groupPresets.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(group.title, color = Accent, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    groupPresets.forEach { preset ->
                        StrategyPresetRow(
                            preset = preset,
                            selected = command.trim() == preset.command ||
                                (command.isBlank() && current == preset.id),
                            onClick = { onSelect(preset.id) },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        // Inline explanation of the currently selected method.
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
                "Активная стратегия: $activeLabel",
                color = OkGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "После смены метода выключи и включи VPN.",
            color = TextSecondary, style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun StrategyPresetRow(
    preset: ByedpiPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.16f) else SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.Check else Icons.Default.Tune,
            contentDescription = if (selected) "Выбрано" else null,
            tint = if (selected) Accent else TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                preset.label,
                color = TextPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                preset.description,
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                preset.command,
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent else SurfaceVariant)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onSelect(value) }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Background else TextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun DiagnosticPresetCard(
    current: String,
    onSelect: (String) -> Unit,
) {
    Card {
        Text("Диагностика трубы", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Проверяет обычное подключение без desync. Нужен только чтобы понять, где проблема: в сети или в методе обхода.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        PresetChip(
            "Проверить без обхода",
            DesyncVpnService.PRESET_OFF,
            current,
            Modifier.fillMaxWidth(),
            onSelect,
        )
    }
}

@Composable
private fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    probe: ProbeUiState,
    onCheck: () -> Unit,
    command: String,
    presetDefault: String,
    onApplyCmd: (String) -> Unit,
    preset: String,
    onSelectPreset: (String) -> Unit,
    allApps: Boolean,
    onAllApps: (Boolean) -> Unit,
    excludedCount: Int,
    onOpenExclusions: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Clickable header that expands the advanced controls.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(14.dp))
                .clickable { onToggle() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Tune, null, tint = Mauve, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Продвинутые настройки", color = TextPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Диагностика, команда byedpi, охват приложений",
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                )
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                null, tint = TextSecondary, modifier = Modifier.size(22.dp)
            )
        }

        if (expanded) {
            DiagnosticPresetCard(preset, onSelectPreset)
            ProbeCard(probe, onCheck)
            ByedpiCommandCard(
                command = command,
                presetDefault = presetDefault,
                onApply = onApplyCmd,
            )
            ToggleCard(
                icon = Icons.Default.Lock,
                title = "Все приложения",
                subtitle = if (allApps)
                    "Обход применяется ко всем приложениям (рекомендуется — надёжнее всего)"
                else
                    "Только YouTube и Instagram. Часть их трафика идёт через Play Services / браузер и может не обходиться — включи «все приложения», если не работает",
                checked = allApps,
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
                Icon(Icons.Default.Block, null, tint = Mauve, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Не использовать обход для…", color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$excludedCount приложений исключено (банки, Госуслуги и др. — чтобы не ловили VPN). Действует в режиме «все приложения»",
                        color = TextSecondary, style = MaterialTheme.typography.labelSmall
                    )
                }
                Icon(Icons.Default.KeyboardArrowDown, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ByedpiCommandCard(
    command: String,
    presetDefault: String,
    onApply: (String) -> Unit,
) {
    var text by remember(command) { mutableStateOf(command) }
    Card {
        Text("Команда byedpi (для продвинутых)", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Тонкая настройка движка обхода. Пусто = стратегия выбранного метода. " +
                "Сейчас активно: ${command.ifBlank { presetDefault.ifBlank { "(без десинка)" } }}",
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent)
                    .clickable { onApply(text.trim()) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Применить", color = Background, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceVariant)
                    .clickable { text = ""; onApply("") }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Сброс", color = TextPrimary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Движок — byedpi v0.17.3 (как в ByeByeDPI), поэтому команды из чатов BBD вставляются " +
                "как есть: -H:\"домены\"(хостлист) -An/-L(авто-секции) -Kt(TLS) -f1+s(fake у SNI) " +
                "-t8(TTL) -s1+s(сплит) -d1+s(disorder) -r1+s(TLS-record) -M(mod-http) -Q(fake-tls). " +
                "ip/порт прокси добавляются автоматически. После «Применить» выключи и включи VPN.",
            color = TextSecondary, style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ToggleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card {
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
                    checkedThumbColor = Background,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = SurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun RestartHintCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Warning.copy(alpha = 0.12f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Info, null, tint = Warning, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Изменения метода и настроек применятся после выключения и повторного включения.",
            color = TextPrimary, style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DisclaimerCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Icon(Icons.Default.Info, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Бета-функция", color = TextPrimary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(
                "Это локальный VPN: трафик не уходит на чужой сервер, приложение лишь меняет формат первых байтов соединения, чтобы провайдер не видел адрес. Работает не у всех операторов — если не помогло, попробуйте другой метод.",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content
    )
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
            Text("Не использовать обход для…", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Отмеченные приложения идут мимо обхода (для банков и приложений, что ловят VPN). Действует в режиме «все приложения». После изменений перезапусти VPN.",
                color = TextSecondary, style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск приложения", color = TextMuted) },
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
                    Text("Загружаю список приложений…", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val filtered = apps.filter {
                    query.isBlank() || it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
                }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
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
                                    if (app.builtIn) "встроенное исключение" else app.pkg,
                                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Готово", onClick = onClose)
        }
    }
}

private fun formatBytesShort(b: Long): String {
    if (b < 1024) return "$b Б"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb)
    return String.format(Locale.US, "%.2f ГБ", mb / 1024.0)
}
