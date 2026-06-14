package com.tgwsproxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
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
    val running = state.isRunning

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item { HeroUnblockCard(running, state, onEnable, onDisable) }

        item { ServicesRow(probe) }

        item { ProbeCard(probe) { vm.runProbe() } }

        if (running) {
            item { LiveStatsCard(state) }
        }

        item { Text("Настройки", style = MaterialTheme.typography.labelLarge, color = TextSecondary) }

        item { PresetCard(settings.preset, running) { vm.setPreset(it) } }

        item {
            ByedpiCommandCard(
                command = settings.byedpiCmd,
                presetDefault = vm.defaultCmdForPreset(settings.preset),
                onApply = { vm.setByedpiCmd(it) },
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
            ToggleCard(
                icon = Icons.Default.Lock,
                title = "Все приложения",
                subtitle = if (settings.allApps)
                    "Обход применяется ко всем приложениям"
                else
                    "Сейчас обход работает только для YouTube и Instagram",
                checked = settings.allApps,
                onChange = { vm.setAllApps(it) }
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
                .background(accent.copy(alpha = 0.15f)),
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
        Text(
            if (running) "Включено — обходим блокировку провайдера"
            else "Выключено. Нажмите, чтобы включить обход",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
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
                Text("Проверить методы", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Напрямую проверяет, какой метод обходит блокировку у твоего оператора. Лучше при выключенном VPN.",
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
                    "Найден рабочий метод — он выставлен автоматически. Включи VPN и проверь приложения."
                else
                    "Простой desync (A/B) не пробивает DPI у твоего оператора. Нужен метод посильнее (FAKE+TTL) — следующая итерация.",
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

@Composable
private fun PresetCard(current: String, running: Boolean, onSelect: (String) -> Unit) {
    Card {
        Text("Метод обхода", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Если что-то не открывается — переключите метод.",
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
        Spacer(Modifier.height(6.dp))
        Text(
            "«Без обхода» гоняет трафик через VPN без десинка — для диагностики: если так грузит, а с обходом нет — дело в методе; если и так не грузит — в трубе. После смены метода выключи и включи VPN.",
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
            placeholder = { Text(presetDefault.ifBlank { "-Kt -An -f1+s -t8" }, color = TextMuted) },
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
            "Флаги byedpi: -Kt(TLS) -An(авто) -f1+s(fake у SNI) -t8(TTL фейка) -s1+s(сплит) -d1+s(disorder) -r1+s(TLS-record). " +
                "После «Применить» выключи и включи VPN.",
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
