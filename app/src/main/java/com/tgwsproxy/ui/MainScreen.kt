package com.tgwsproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.ExperimentalMaterial3Api
import com.tgwsproxy.R
import com.tgwsproxy.ui.theme.*

private fun copyToClipboard(context: Context, label: String, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13+ shows its own clipboard confirmation chip.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ProxyViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Logs are hidden by default — the user opens them only when needed.
    var logsExpanded by remember { mutableStateOf(false) }

    // Smart auto-scroll: only when the user is already at the bottom
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    LaunchedEffect(uiState.logs.size, isAtBottom) {
        val totalItems = listState.layoutInfo.totalItemsCount
        if (isAtBottom && totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_jevio_logo),
                            contentDescription = "Jevio",
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Jevio",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary
                            )
                            Text(
                                "Telegram без блокировок",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background
                ),
                actions = {
                    if (uiState.logs.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Очистить логи",
                                tint = TextSecondary
                            )
                        }
                    }
                }
            )
        },
        containerColor = Background
    ) { padding ->

        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Accent)
            }
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { HeroStatusCard(uiState) }

            item { BatteryOptimizationCard() }

            item {
                ControlButtons(
                    uiState = uiState,
                    onToggle = { viewModel.toggleProxy() },
                    onOpenTelegram = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.proxyLink))
                        context.startActivity(intent)
                    },
                    onCopyLink = {
                        copyToClipboard(context, "TG Proxy", uiState.proxyLink, "Ссылка скопирована")
                    }
                )
            }

            item {
                ProxyInfoCard(
                    uiState = uiState,
                    context = context,
                    onRegenerateSecret = { viewModel.regenerateSecret() }
                )
            }

            item { SettingsCard(uiState, onSaveCfDomain = { viewModel.setCfDomain(it) }) }

            if (uiState.logs.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { logsExpanded = !logsExpanded }
                            .padding(top = 4.dp, bottom = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Логи",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextSecondary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${uiState.logs.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Icon(
                            imageVector = if (logsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (logsExpanded) "Скрыть логи" else "Показать логи",
                            tint = TextSecondary
                        )
                    }
                }

                if (logsExpanded) {
                    items(uiState.logs, key = null) { log ->
                        LogItem(log)
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HeroStatusCard(uiState: ProxyUiState) {
    val running = uiState.isRunning
    val statusColor by animateColorAsState(
        targetValue = if (running) AccentSoft else Destructive,
        animationSpec = tween(300),
        label = "statusColor"
    )

    // Gentle pulsing for the live status dot.
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = if (running) {
                            listOf(Color(0xFF0E2A22), Surface)
                        } else {
                            listOf(Color(0xFF2A1115), Surface)
                        }
                    )
                )
                .padding(22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Статус прокси",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (running) "Активен" else "Остановлен",
                        style = MaterialTheme.typography.headlineSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                        .alpha(if (running) pulse else 1f)
                )
            }

            if (running) {
                Spacer(modifier = Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatChip(
                        icon = Icons.Default.Link,
                        label = "Подключения",
                        value = uiState.connectionCount.toString()
                    )
                    StatChip(
                        icon = Icons.Default.Dns,
                        label = "Порт",
                        value = uiState.port.toString()
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x33000000))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AccentSoft, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
private fun ProxyInfoCard(
    uiState: ProxyUiState,
    context: Context,
    onRegenerateSecret: () -> Unit
) {
    var secretRevealed by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CopyableRow(
                label = "Сервер",
                value = "${uiState.host}:${uiState.port}",
                onCopy = { copyToClipboard(context, "Сервер", "${uiState.host}:${uiState.port}", "Скопировано") }
            )

            HorizontalDivider(color = Border)

            // Secret row with tap-to-reveal + copy (48dp minimum touch target)
            Column {
                Text(
                    text = "Секрет",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = if (secretRevealed || uiState.secret.isEmpty()) {
                            uiState.secret.ifEmpty { "—" }
                        } else {
                            "•".repeat(minOf(uiState.secret.length, 20))
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.weight(1f))
                    if (uiState.secret.isNotEmpty()) {
                        IconButton(onClick = { secretRevealed = !secretRevealed }) {
                            Icon(
                                imageVector = if (secretRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (secretRevealed) "Скрыть" else "Показать",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = {
                            copyToClipboard(context, "Секрет", uiState.secret, "Секрет скопирован")
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Копировать секрет",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        // Rotate the key — only while stopped (changing it needs a re-add in TG).
                        if (!uiState.isRunning) {
                            IconButton(onClick = onRegenerateSecret) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Сменить секрет",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (uiState.isRunning) {
                        "Секрет постоянный — ссылку в Telegram повторно добавлять не нужно."
                    } else {
                        "Секрет сохраняется между запусками. Нажмите ↻, чтобы сгенерировать новый."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CopyableRow(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Копировать",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(uiState: ProxyUiState, onSaveCfDomain: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var domainInput by remember(uiState.cfDomain) { mutableStateOf(uiState.cfDomain) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Обход блокировок",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Свернуть" else "Развернуть",
                        tint = TextSecondary
                    )
                }
            }

            Text(
                text = "Если сеть блокирует Telegram, прокси автоматически идёт через Cloudflare. Можно указать свой домен (надёжнее, чем общие).",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            if (expanded) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    label = { Text("Свой Cloudflare-домен", color = TextSecondary) },
                    placeholder = { Text("example.com", color = TextSecondary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        cursorColor = Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Оставьте пустым, чтобы использовать встроенные домены.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onSaveCfDomain(domainInput.trim()) },
                    enabled = domainInput.trim() != uiState.cfDomain,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сохранить", fontWeight = FontWeight.SemiBold)
                }
                if (uiState.isRunning) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Изменения применятся после перезапуска прокси.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Warning
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButtons(
    uiState: ProxyUiState,
    onToggle: () -> Unit,
    onOpenTelegram: () -> Unit,
    onCopyLink: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isRunning) Destructive else Accent
            )
        ) {
            Icon(
                imageVector = if (uiState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (uiState.isRunning) "Остановить прокси" else "Запустить прокси",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (uiState.isRunning && uiState.proxyLink.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onOpenTelegram,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Подключить", fontWeight = FontWeight.Medium)
                }

                OutlinedButton(
                    onClick = onCopyLink,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = BorderStroke(1.dp, Border)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ссылка", color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun LogItem(log: String) {
    val color = when {
        log.contains("ERROR", ignoreCase = true)          -> Destructive
        log.contains("failed", ignoreCase = true)         -> Warning
        log.contains("WARN", ignoreCase = true)           -> Warning
        log.contains("handshake ok", ignoreCase = true)   -> AccentSoft
        log.contains("Cloudflare", ignoreCase = true)     -> Info
        log.contains("WS connected", ignoreCase = true)   -> Info
        else -> TextSecondary
    }

    Text(
        text = log,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp
        ),
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(LogSurface)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * Warns the user when Jevio is NOT exempt from battery optimization. In that
 * state Android (especially MIUI/EMUI/Samsung) can freeze the foreground
 * service in the background, which silently drops the proxy until the app is
 * reopened. The button launches the system dialog to whitelist the app.
 * The card hides itself automatically once the exemption is granted.
 */
@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun isIgnoring(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    var ignoring by remember { mutableStateOf(isIgnoring()) }

    // Re-check whenever the user comes back to the app (e.g. after the dialog).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ignoring = isIgnoring()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (ignoring) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Warning.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BatteryAlert,
                    contentDescription = null,
                    tint = Warning
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.battery_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.battery_warning_text),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    try {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // Fallback to the generic battery-optimization settings list.
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Warning)
            ) {
                Text(
                    text = stringResource(R.string.battery_warning_button),
                    color = Background,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
