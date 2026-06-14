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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgwsproxy.ui.theme.*
import com.tgwsproxy.vpn.DesyncVpnService
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
    var advancedOpen by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item { HeroUnblockCard(running, state, onEnable, onDisable) }

        item {
            AutoTuneCard(
                autoTune = autoTune,
                vpnRunning = running,
                onRun = { vm.runAutoTune() },
                onReset = { vm.dismissAutoTune() },
            )
        }

        item { ServicesRow(probe) }

        if (running) {
            item { LiveStatsCard(state) }
        }

        item { Text("Метод обхода", style = MaterialTheme.typography.labelLarge, color = TextSecondary) }

        item { PresetCard(settings.preset, running) { vm.setPreset(it) } }

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
                allApps = settings.allApps,
                onAllApps = { vm.setAllApps(it) },
            )
        }

        if (running) {
            item { RestartHintCard() }
        }

        item { DisclaimerCard() }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HeroUnblockCard(
    running: Boolean,
    state: DesyncVpnService.VpnState,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    val accent = if (running) Accent else TextMuted

    // Gentle breathing pulse on the status badge while the VPN is active.
    val pulse = rememberInfiniteTransition(label = "hero-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "hero-pulse-alpha"
    )
    val badgeAlpha = if (running) pulseAlpha else 0.15f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    if (running) listOf(Color(0xFF2B2540), Surface) else listOf(Surface, Surface)
                )
            )
            .border(1.dp, if (running) Accent.copy(alpha = 0.45f) else Border, RoundedCornerShape(20.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = badgeAlpha)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (running) Icons.Default.Check else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(38.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Разблокировка YouTube и Instagram",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(OkGreen).alpha(pulseAlpha * 3.4f))
                Text(
                    "Включено — обходим блокировку провайдера",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        } else {
            Text(
                "Выключено. Нажмите, чтобы включить обход",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Destructive)
        }
        Spacer(Modifier.height(16.dp))
        BigToggleButton(running) { if (running) onDisable() else onEnable() }
    }
}

@Composable
private fun BigToggleButton(running: Boolean, onClick: () -> Unit) {
    val bg = if (running) Destructive else Accent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (running) "Выключить" else "Включить разблокировку",
            color = Background,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

private val OkGreen = Color(0xFF5BBF7B)

@Composable
private fun AutoTuneCard(
    autoTune: AutoTuneUiState,
    vpnRunning: Boolean,
    onRun: () -> Unit,
    onReset: () -> Unit,
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoFixHigh, null, tint = Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Автоподбор метода", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    "Сами переберём методы и оставим рабочий",
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
                        "Проверяю ${autoTune.index}/${autoTune.total}: ${autoTune.currentLabel}",
                        color = TextPrimary, style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Не закрывай вкладку — подбор идёт через реальные подключения к YouTube и Instagram.",
                    color = TextMuted, style = MaterialTheme.typography.labelSmall
                )
            }

            autoTune.finished && autoTune.foundLabel != null -> {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Check, null, tint = OkGreen, modifier = Modifier.size(18.dp))
                    Text(
                        "Готово: «${autoTune.foundLabel}» подобран и сохранён. Включи VPN и проверь приложения.",
                        color = OkGreen, style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(10.dp))
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
                        color = TextMuted, style = MaterialTheme.typography.labelSmall
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Accent else SurfaceVariant)
            .clickable(enabled = enabled) { onClick() }
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariant)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ServicesRow(probe: ProbeUiState) {
    val yt = probe.results.firstOrNull { it.display == "YouTube" }
    val ig = probe.results.firstOrNull { it.display == "Instagram" }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ServiceChip("YouTube", yt, probe.checking, Modifier.weight(1f))
        ServiceChip("Instagram", ig, probe.checking, Modifier.weight(1f))
    }
}

@Composable
private fun ServiceChip(
    name: String,
    result: ServiceProbe?,
    checking: Boolean,
    modifier: Modifier = Modifier,
) {
    // Status: green if a method bypasses DPI, red if all blocked, amber on error, grey unknown.
    val (dotColor, statusText) = when {
        checking -> SurfaceVariant to "проверка…"
        result == null -> SurfaceVariant to "не проверено"
        result.anyPass -> OkGreen to "работает"
        result.plain == com.tgwsproxy.net.HelloProbe.Outcome.BLOCKED -> Destructive to "заблокировано"
        else -> Warning to "не удалось"
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
            } else if (result?.anyPass == true) {
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
            Text("Диагностика", color = TextMuted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(2.dp))
            Text(err, color = Destructive, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

/** Human description of what each preset actually does — shown under the chips. */
private fun presetDescription(preset: String): String = when (preset) {
    DesyncVpnService.PRESET_AUTO ->
        "Сильный каскадный сплит. Рекомендуется — пробивает большинство операторов."
    DesyncVpnService.PRESET_TLSREC ->
        "Метод A: сплит + tlsrec + FAKE-пакет с низким TTL. Бери, если «Авто» не справился."
    DesyncVpnService.PRESET_SPLIT ->
        "Метод B: лёгкий многоточечный сплит. Ниже задержка, запасной вариант."
    DesyncVpnService.PRESET_OFF ->
        "Без десинка: трафик идёт через VPN как есть. Только для диагностики «трубы»."
    else -> ""
}

@Composable
private fun PresetCard(current: String, running: Boolean, onSelect: (String) -> Unit) {
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
        PresetChip("Без обхода (тест трубы)", DesyncVpnService.PRESET_OFF, current, Modifier.fillMaxWidth(), onSelect)

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
                presetDescription(current),
                color = TextSecondary, style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "После смены метода выключи и включи VPN.",
            color = TextMuted, style = MaterialTheme.typography.labelSmall
        )
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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent else SurfaceVariant)
            .clickable { onSelect(value) }
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
private fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    probe: ProbeUiState,
    onCheck: () -> Unit,
    command: String,
    presetDefault: String,
    onApplyCmd: (String) -> Unit,
    allApps: Boolean,
    onAllApps: (Boolean) -> Unit,
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
                    "Ручная проверка, команда byedpi, охват приложений",
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                )
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                null, tint = TextSecondary, modifier = Modifier.size(22.dp)
            )
        }

        if (expanded) {
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
                    "Обход применяется ко всем приложениям"
                else
                    "Сейчас обход работает только для YouTube и Instagram",
                checked = allApps,
                onChange = onAllApps
            )
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
            color = TextMuted, style = MaterialTheme.typography.labelSmall
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
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .padding(16.dp),
        content = content
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
