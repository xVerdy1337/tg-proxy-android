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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import java.util.Locale

private fun copyToClipboard(context: Context, label: String, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }
}

private fun formatBytes(b: Long): String {
    if (b < 1024) return "$b Б"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb)
    return String.format(Locale.US, "%.2f ГБ", mb / 1024.0)
}

private fun formatUptime(startedAt: Long, now: Long): String {
    if (startedAt <= 0) return "—"
    val s = ((now - startedAt) / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

private fun routeLabel(route: String): String = when (route) {
    "cloudflare" -> "Cloudflare"
    "direct" -> "Прямое"
    "tcp" -> "TCP"
    else -> "—"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ProxyViewModel = viewModel(),
    desyncVm: DesyncViewModel = viewModel(),
    onEnableVpn: () -> Unit = {},
    onDisableVpn: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var logsExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var advancedTgOpen by remember { mutableStateOf(false) }

    var showOnboarding by remember { mutableStateOf(shouldShowOnboarding(context)) }
    if (showOnboarding) {
        OnboardingDialog(onDismiss = {
            markOnboardingShown(context)
            showOnboarding = false
        })
    }

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
                            contentDescription = "Jevio Unblocker",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Jevio Unblocker",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background
                ),
                actions = {
                    if (uiState.logs.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(
                                imageVector = Icons.Default.ClearAll,
                                contentDescription = "Очистить журнал",
                                tint = TextSecondary
                            )
                        }
                    }
                }
            )
        },
        containerColor = Background
    ) { padding ->

      Column(modifier = Modifier.fillMaxSize().padding(padding)) {

        UpdateBanner(context = context)

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Background,
            contentColor = Accent,
            divider = { HorizontalDivider(color = Border.copy(alpha = 0.65f)) }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                icon = {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = null,
                        tint = if (selectedTab == 0) Accent else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                text = { Text("Telegram", color = if (selectedTab == 0) Accent else TextSecondary) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                icon = {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = "Разблокировка сайтов",
                        tint = if (selectedTab == 1) Accent else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                text = { Text("YouTube · Instagram", color = if (selectedTab == 1) Accent else TextSecondary) }
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
          when (selectedTab) {
            1 -> UnblockScreen(desyncVm, onEnableVpn, onDisableVpn)
            else -> if (uiState.isLoading) {
              Box(
                  modifier = Modifier.fillMaxSize(),
                  contentAlignment = Alignment.Center
              ) {
                  Column(horizontalAlignment = Alignment.CenterHorizontally) {
                      CircularProgressIndicator(color = Accent)
                      Spacer(modifier = Modifier.height(14.dp))
                      Text("Подключаюсь…", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                  }
              }
            } else {
              LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
              ) {
            item { HeroStatusCard(uiState) }

            item {
                ControlButtons(
                    uiState = uiState,
                    onToggle = { viewModel.toggleProxy() },
                    onOpenTelegram = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.proxyLink))
                        context.startActivity(intent)
                    }
                )
            }

            // Keep the primary action close to the status card. Battery guidance is useful,
            // but should not interrupt the first successful connection.
            item { BatteryOptimizationCard() }

            item { TgAdvancedHeader(advancedTgOpen) { advancedTgOpen = !advancedTgOpen } }

            if (advancedTgOpen) {
                item {
                    ProxyInfoCard(
                        uiState = uiState,
                        context = context,
                        onRegenerateSecret = { viewModel.regenerateSecret() }
                    )
                }
                item { FakeTlsCard(uiState, onSave = { viewModel.setFakeTlsDomain(it) }) }
                item { SettingsCard(uiState, onSaveCfDomain = { viewModel.setCfDomain(it) }, onSaveCfWorkerDomain = { viewModel.setCfWorkerDomain(it) }) }
            }

            item { TelegramChannelCard(context) }

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
        }
      }
    }
}

@Composable
private fun HeroStatusCard(uiState: ProxyUiState) {
    val running = uiState.isRunning
    val statusColor by animateColorAsState(
        targetValue = if (running) AccentSoft else TextMuted,
        animationSpec = tween(300),
        label = "statusColor"
    )

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

    // Live ticking clock for the uptime readout.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(
            1.dp,
            if (running) Success.copy(alpha = 0.42f) else Border.copy(alpha = 0.85f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = if (running) {
                            listOf(Color(0xFF3A2740), Surface)
                        } else {
                            listOf(Color(0xFF2A2233), Surface)
                        }
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Статус прокси",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (running) "Активен" else "Остановлен",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (running) Success else statusColor,
                        fontWeight = FontWeight.Bold
                    )
                    if (running && uiState.route.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CloudDone,
                                contentDescription = null,
                                tint = Mauve,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Маршрут: ${routeLabel(uiState.route)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (running) {
                            "Трафик Telegram проходит через локальный прокси."
                        } else {
                            "Запустите прокси, чтобы получить ссылку для подключения."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (running) Success else statusColor)
                        .alpha(if (running) pulse else 1f)
                )
            }

            AnimatedVisibility(visible = running) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatChip(
                            icon = Icons.Default.Timer,
                            label = "Аптайм",
                            value = formatUptime(uiState.startedAt, now)
                        )
                        StatChip(
                            icon = Icons.Default.Link,
                            label = "Подключения",
                            value = uiState.connectionCount.toString()
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatChip(
                            icon = Icons.Default.ArrowUpward,
                            label = "Отправлено",
                            value = formatBytes(uiState.bytesUp),
                            tint = Mauve
                        )
                        StatChip(
                            icon = Icons.Default.ArrowDownward,
                            label = "Получено",
                            value = formatBytes(uiState.bytesDown),
                            tint = Primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    tint: Color = AccentSoft
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x40000000))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            // tnum = tabular figures: these values tick every second (uptime/bytes/connections),
            // and proportional digits would shift width each update, jittering the layout.
            Text(
                value,
                // tnum lives on TextStyle, not as a Text() argument — merge it into the style.
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
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
        shape = RoundedCornerShape(18.dp),
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
                    text = if (uiState.fakeTlsDomain.isNotEmpty()) {
                        "Режим Fake TLS (ee-секрет) под домен ${uiState.fakeTlsDomain}."
                    } else if (uiState.isRunning) {
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

/**
 * Fake-TLS card. Lets the user mask the proxy as HTTPS to a real domain (the strongest DPI
 * bypass) by entering a masking domain — the link switches to an `ee...` secret. Empty = off.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FakeTlsCard(uiState: ProxyUiState, onSave: (String) -> Unit) {
    val enabled = uiState.fakeTlsDomain.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }
    var domainInput by remember(uiState.fakeTlsDomain) { mutableStateOf(uiState.fakeTlsDomain) }

    val presets = listOf("www.microsoft.com", "www.cloudflare.com", "dl.google.com", "www.bing.com")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = if (enabled) BorderStroke(1.dp, Primary.copy(alpha = 0.45f)) else null
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = if (enabled) Primary else TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Fake TLS маскировка",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (enabled) "Вкл · ${uiState.fakeTlsDomain}" else "Выкл",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (enabled) Primary else TextSecondary
                        )
                    }
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
                text = "Маскирует прокси под HTTPS к настоящему сайту — самый надёжный обход DPI. Укажите домен, и ссылка станет ee-секретом.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Text("Быстрый выбор домена:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { p ->
                            val sel = domainInput.trim() == p
                            Text(
                                text = p,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sel) Background else TextPrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) Primary else SurfaceVariant)
                                    .clickable { domainInput = p }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text("Домен маскировки", color = TextSecondary) },
                        placeholder = { Text("www.example.com", color = TextSecondary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Border,
                            cursorColor = Primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onSave(domainInput.trim()) },
                            enabled = domainInput.trim() != uiState.fakeTlsDomain,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Background)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Включить", fontWeight = FontWeight.SemiBold)
                        }
                        if (enabled) {
                            OutlinedButton(
                                onClick = { domainInput = ""; onSave("") },
                                modifier = Modifier.height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                border = BorderStroke(1.dp, Border)
                            ) {
                                Text("Выключить", color = TextSecondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "После изменения добавьте новую ссылку в Telegram заново.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Warning
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(uiState: ProxyUiState, onSaveCfDomain: (String) -> Unit, onSaveCfWorkerDomain: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var domainInput by remember(uiState.cfDomain) { mutableStateOf(uiState.cfDomain) }
    var workerInput by remember(uiState.cfWorkerDomain) { mutableStateOf(uiState.cfWorkerDomain) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
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

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text("Свои Cloudflare-домены", color = TextSecondary) },
                        placeholder = { Text("example.com, mydomain.com", color = TextSecondary) },
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
                        text = "Несколько доменов — через запятую. Оставьте пустым, чтобы использовать встроенные домены.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onSaveCfDomain(domainInput.trim()) },
                        enabled = domainInput.trim() != uiState.cfDomain,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Сохранить", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "Cloudflare Worker — бесплатно, без покупки домена. Разверните воркер по инструкции (docs/CfWorker.md) и вставьте его адрес *.workers.dev.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = workerInput,
                        onValueChange = { workerInput = it },
                        label = { Text("Cloudflare Worker домен", color = TextSecondary) },
                        placeholder = { Text("name.username.workers.dev", color = TextSecondary) },
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
                        text = "Несколько воркеров — через запятую. Оставьте пустым, чтобы не использовать.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onSaveCfWorkerDomain(workerInput.trim()) },
                        enabled = workerInput.trim() != uiState.cfWorkerDomain,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Сохранить Worker", fontWeight = FontWeight.SemiBold)
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
}

@Composable
private fun ControlButtons(
    uiState: ProxyUiState,
    onToggle: () -> Unit,
    onOpenTelegram: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "proxyButtonScale"
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                },
            interactionSource = interaction,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isRunning) Color.Transparent else Primary,
                contentColor = if (uiState.isRunning) Destructive else Background
            ),
            border = if (uiState.isRunning) BorderStroke(1.dp, Destructive) else null
        ) {
            Icon(
                imageVector = if (uiState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (uiState.isRunning) "Остановить прокси" else "Запустить прокси",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (uiState.isRunning && uiState.proxyLink.isNotEmpty()) {
            // Single full-width "open in Telegram" action — the primary thing the user wants
            // after the proxy is up. Copying the raw link lived in a separate button that just
            // added clutter; the secret/server rows already have their own copy icons.
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenTelegram()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Подключить Telegram",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
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
        log.contains("Fake TLS", ignoreCase = true)       -> Primary
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
 * Warns the user when Jevio is NOT exempt from battery optimization. The button launches
 * the system whitelist dialog; the card hides itself once the exemption is granted.
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

@Composable
private fun TelegramChannelCard(context: Context) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/jevio_dev"))
                    )
                } catch (_: Exception) {}
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Наш Telegram-канал",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "@jevio_dev — новости и обновления",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun TgAdvancedHeader(expanded: Boolean, onToggle: () -> Unit) {
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
        Icon(Icons.Default.Tune, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Продвинутые настройки", color = TextPrimary, fontWeight = FontWeight.Medium)
            Text(
                "Сервер и секрет, Fake TLS, свой Cloudflare-домен",
                color = TextSecondary, style = MaterialTheme.typography.labelSmall
            )
        }
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null, tint = TextSecondary, modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun UpdateBanner(context: Context) {
    var updateUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        updateUrl = withContext(Dispatchers.IO) { checkForUpdate(context) }
    }
    val url = updateUrl ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Accent.copy(alpha = 0.15f))
            .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Доступна новая версия", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "Нажмите, чтобы скачать обновление",
                color = TextSecondary, style = MaterialTheme.typography.labelSmall
            )
        }
        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
    }
}

private fun currentVersionName(context: Context): String {
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }
}

private fun parseVersion(raw: String): List<Int> {
    return raw.trim().removePrefix("v").removePrefix("V")
        .split(Regex("[._-]"))
        .mapNotNull { part -> part.takeWhile { c -> c.isDigit() }.toIntOrNull() }
}

private fun isNewer(latest: String, current: String): Boolean {
    val a = parseVersion(latest); val b = parseVersion(current)
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun checkForUpdate(context: Context): String? {
    return try {
        val url = java.net.URL("https://api.github.com/repos/xVerdy1337/tg-proxy-android/releases/latest")
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 4000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "JevioUnblocker")
        }
        if (conn.responseCode != 200) return null
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = org.json.JSONObject(text)
        val tag = json.optString("tag_name", "")
        val safeReleasesUrl = "https://github.com/xVerdy1337/tg-proxy-android/releases/latest"
        val htmlUrl = json.optString("html_url", safeReleasesUrl)
        // The URL comes from the API response and is later opened via ACTION_VIEW. Only trust it
        // if it's an https github.com link — a tampered/compromised response could otherwise hand
        // us an intent:// or arbitrary-scheme URI. Fall back to the known-good releases page.
        val openUrl = if (isTrustedGithubUrl(htmlUrl)) htmlUrl else safeReleasesUrl
        if (tag.isNotEmpty() && isNewer(tag, currentVersionName(context))) openUrl else null
    } catch (e: Exception) { null }
}

/** True only for https://github.com/... URLs (host exactly github.com or a subdomain). */
private fun isTrustedGithubUrl(url: String): Boolean = try {
    val u = java.net.URI(url)
    u.scheme.equals("https", ignoreCase = true) &&
        u.host?.let { it.equals("github.com", true) || it.endsWith(".github.com", true) } == true
} catch (e: Exception) { false }

private const val PREFS_ONBOARDING = "jevio_onboarding"
private const val KEY_ONBOARDING_SHOWN = "onboarding_shown"

private fun shouldShowOnboarding(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_ONBOARDING, Context.MODE_PRIVATE)
    return !prefs.getBoolean(KEY_ONBOARDING_SHOWN, false)
}

private fun markOnboardingShown(context: Context) {
    context.getSharedPreferences(PREFS_ONBOARDING, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
}

@Composable
private fun OnboardingDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Surface)
                .padding(24.dp)
        ) {
            Text(
                "Добро пожаловать в Jevio Unblocker",
                color = TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(18.dp))
            OnboardingRow(
                Icons.Default.Send,
                "Telegram",
                "Прокси для Telegram, если он заблокирован."
            )
            Spacer(Modifier.height(14.dp))
            OnboardingRow(
                Icons.Default.PlayArrow,
                "YouTube · Instagram",
                "Разблокировка через VPN. Нажмите «Подобрать автоматически» и включайте."
            )
            Spacer(Modifier.height(14.dp))
            OnboardingRow(
                Icons.Default.Lock,
                "Банки и Госуслуги",
                "Эти приложения идут напрямую — VPN их не трогает."
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("Понятно, начать", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OnboardingRow(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(body, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
