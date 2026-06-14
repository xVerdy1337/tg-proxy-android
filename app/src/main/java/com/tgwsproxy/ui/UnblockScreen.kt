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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val running = state.isRunning

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item { HeroUnblockCard(running, state, onEnable, onDisable) }

        item { ServicesRow(running) }

        if (running) {
            item { LiveStatsCard(state) }
        }

        item { Text("Настройки", style = MaterialTheme.typography.labelLarge, color = TextSecondary) }

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

@Composable
private fun ServicesRow(running: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ServiceChip("YouTube", running, Modifier.weight(1f))
        ServiceChip("Instagram", running, Modifier.weight(1f))
    }
}

@Composable
private fun ServiceChip(name: String, running: Boolean, modifier: Modifier = Modifier) {
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
                .background(if (running) Accent.copy(alpha = 0.2f) else SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (running) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(14.dp))
        }
        Text(name, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
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
