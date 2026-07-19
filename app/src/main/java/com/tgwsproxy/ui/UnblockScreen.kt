package com.tgwsproxy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import java.util.Locale

private val OkGreen = Success

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
internal fun reducedMotionEnabled(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
}

/**
 * Snappy, custom-curve press feedback (Emil: ~140ms, strong ease-out, interruptible).
 * Never go below 0.95 — it starts to feel exaggerated.
 */
@Composable
private fun rememberPressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 140, easing = EmilEaseOut),
        label = "pressScale"
    )
    return scale
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

    item(key = "services") {
        val probe by vm.probe.collectAsState()
        val autoTune by vm.autoTune.collectAsState()
        ServicesCard(probe, autoTune)
    }

    item(key = "unblock-live-stats") {
        val state by vm.vpnState.collectAsState()
        AnimatedVisibility(
            visible = state.isRunning,
            enter = fadeIn(tween(220, easing = EmilEaseOut)) + expandVertically(tween(220, easing = EmilEaseOut)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
        ) { LiveStatsCard(state) }
    }

    item(key = "auto-tune") {
        val autoTune by vm.autoTune.collectAsState()
        val state by vm.vpnState.collectAsState()
        AutoTuneCard(
            autoTune = autoTune,
            vpnRunning = state.isRunning,
            onRun = { vm.runAutoTune() },
            onReset = { vm.dismissAutoTune() },
            onEnable = onEnable,
        )
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
    val busy = starting || testing
    val haptic = LocalHapticFeedback.current

    JevioGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Сайты",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "Локальный обход",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (running && !busy) Signal else SurfaceVariant)
                    .border(
                        width = 1.dp,
                        color = if (running && !busy) Signal else GlassBorder,
                        shape = RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (running && !busy) Primary else TextMuted),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (running && !busy) "В СЕТИ" else if (busy) "ЗАПУСК" else "ГОТОВ",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                    color = TextPrimary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        JevioStateDial(
            active = running,
            busy = busy,
            icon = Icons.Default.Bolt,
        )
        Spacer(Modifier.height(18.dp))

        Text(
            text = when {
                testing -> "Подбор…"
                starting -> "Запуск…"
                running -> "Включено"
                else -> "Выключено"
            },
            color = if (running && !busy) OkGreen else TextPrimary,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                testing -> "Тестируем методы, не закрывайте приложение"
                starting -> "Поднимаем локальный обход"
                running -> "YouTube и Instagram без блокировок"
                else -> "Локальный обход без внешнего VPN"
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

        Spacer(Modifier.height(24.dp))

        SitesPillButton(
            label = when {
                testing -> "Тестирование…"
                starting -> "Запуск…"
                running -> "Выключить"
                else -> "Включить"
            },
            loading = busy,
            destructive = running && !busy,
            enabled = !busy,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                when {
                    starting -> Unit
                    running -> onDisable()
                    else -> onEnable()
                }
            },
        )
    }
}

/** Full-width fully-rounded pill CTA (not a circular icon). */
@Composable
private fun SitesPillButton(
    label: String,
    loading: Boolean,
    destructive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    val bg = when {
        destructive -> GlassSurfaceMuted
        !enabled || loading -> SurfaceVariant
        else -> Accent
    }
    val fg = if (destructive) Destructive else OnAccent
    val border = if (destructive) BorderStroke(1.5.dp, Destructive) else null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .shadow(
                elevation = if (!destructive && enabled && !loading) 9.dp else 0.dp,
                shape = shape,
                ambientColor = GlassShadow,
                spotColor = GlassShadow,
            )
            .clip(shape)
            .background(bg)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = fg,
            )
        } else {
            Text(
                text = label,
                color = fg,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                letterSpacing = (-0.15).sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AutoTuneCard(
    autoTune: AutoTuneUiState,
    vpnRunning: Boolean,
    onRun: () -> Unit,
    onReset: () -> Unit,
    onEnable: () -> Unit,
) {
    when {
        autoTune.running -> {
            PanelCard {
                Text("Подбор метода", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                    Text(
                        "Проверяю ${autoTune.index}/${autoTune.total}",
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
                    "Не закрывайте приложение во время проверки.",
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                )
            }
        }

        autoTune.finished && autoTune.foundLabel != null -> {
            PanelCard {
                Text(
                    "Подобран: «${autoTune.foundLabel}»",
                    color = OkGreen, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Включить", onClick = onEnable)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Подобрать заново", onReset)
            }
        }

        autoTune.finished && autoTune.error != null -> {
            PanelCard {
                Text(autoTune.error, color = Warning, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    if (vpnRunning) "Сначала выключи VPN" else "Попробовать снова",
                    enabled = !vpnRunning,
                    onClick = onRun,
                )
            }
        }

        else -> {
            SecondaryButton(
                if (vpnRunning) "Автоподбор (сначала выключи VPN)" else "Подобрать метод автоматически",
                onClick = { if (!vpnRunning) onRun() },
            )
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
            .heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) Accent else SurfaceVariant)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) OnAccent else TextMuted,
            fontWeight = FontWeight.Medium,
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
            .heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(GlassSurfaceMuted)
            .border(1.dp, Primary.copy(alpha = 0.20f), RoundedCornerShape(999.dp))
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ServicesCard(probe: ProbeUiState, autoTune: AutoTuneUiState) {
    val yt = probe.results.firstOrNull { it.display == "YouTube" }
    val ytv = probe.results.firstOrNull { it.display == "YouTube (видео)" }
    val ig = probe.results.firstOrNull { it.display == "Instagram" }
    val busy = probe.checking || autoTune.running
    PanelCard {
        ServiceRow("YouTube", yt, busy, autoTune.hostOk["YouTube"])
        HorizontalDivider(color = Border.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 2.dp))
        ServiceRow("YouTube (видео)", ytv, busy, autoTune.hostOk["YouTube (видео)"])
        HorizontalDivider(color = Border.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 2.dp))
        ServiceRow("Instagram", ig, busy, autoTune.hostOk["Instagram"])
    }
}

@Composable
private fun ServiceRow(
    name: String,
    result: ServiceProbe?,
    checking: Boolean,
    forced: Boolean?,
) {
    val (dotColor, statusText, working) = when {
        checking -> Triple(SurfaceVariant, "проверка…", false)
        forced == true -> Triple(OkGreen, "работает", true)
        forced == false -> Triple(Destructive, "заблокировано", false)
        result == null -> Triple(SurfaceVariant, "не проверено", false)
        result.anyPass -> Triple(OkGreen, "работает", true)
        result.plain == com.tgwsproxy.net.HelloProbe.Outcome.BLOCKED -> Triple(Destructive, "заблокировано", false)
        else -> Triple(Warning, "не удалось", false)
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            statusText,
            color = statusColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun LiveStatsCard(state: DesyncVpnService.VpnState) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatItem("Соединения", "${state.activeTcp}", Modifier.weight(1f))
            StatItem("Отправлено", formatBytesShort(state.bytesUp), Modifier.weight(1f))
            StatItem("Получено", formatBytesShort(state.bytesDown), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatItem("Подключено", "${state.connOk}", Modifier.weight(1f))
            StatItem("Не дошло", "${state.connFail}", Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
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

    PanelCard {
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
            .heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent else SurfaceVariant)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current) { onSelect(value) }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) OnAccent else TextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge
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
                Text("Настройки", color = TextPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Метод, QUIC, приложения, byedpi",
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
            enter = fadeIn(tween(220, easing = EmilEaseOut)) + expandVertically(tween(220, easing = EmilEaseOut)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PresetCard(
                    current = settings.preset,
                    running = false,
                    activeLabel = if (settings.byedpiCmd.isBlank()) null
                    else com.tgwsproxy.net.StrategyTester.labelForCommand(settings.byedpiCmd) ?: "своя команда",
                    command = settings.byedpiCmd,
                    onSelect = onSelectPreset,
                )
                ToggleCard(
                    icon = Icons.Default.Bolt,
                    title = "Блокировать QUIC",
                    subtitle = "Ускоряет обход для YouTube: обычный TLS вместо QUIC",
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
                    title = "Все приложения",
                    subtitle = if (settings.allApps)
                        "Обход для всех приложений (рекомендуется)"
                    else
                        "Только YouTube и Instagram — включи «все», если не работает",
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
                        Text("Не использовать обход для…", color = TextPrimary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "$excludedCount исключено (банки, Госуслуги…)",
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
private fun ProbeCard(probe: ProbeUiState, onCheck: () -> Unit) {
    PanelCard {
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
                    Text("Проверить", color = OnAccent, fontWeight = FontWeight.SemiBold)
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
private fun ByedpiCommandCard(
    command: String,
    presetDefault: String,
    onApply: (String) -> Unit,
) {
    var text by remember(command) { mutableStateOf(command) }
    PanelCard {
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
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent)
                    .clickable { onApply(text.trim()) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Применить", color = OnAccent, fontWeight = FontWeight.SemiBold)
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

private fun formatBytesShort(b: Long): String {
    if (b < 1024) return "$b Б"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb)
    return String.format(Locale.US, "%.2f ГБ", mb / 1024.0)
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
            foundLabel = "Авто",
            hostOk = mapOf(
                "YouTube" to true,
                "YouTube (видео)" to true,
                "Instagram" to true,
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
                            vpnRunning = false,
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
            vpnRunning = false,
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
