package com.tgwsproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgwsproxy.R
import com.tgwsproxy.service.LogKind
import com.tgwsproxy.service.LogLine
import com.tgwsproxy.ui.theme.Background
import com.tgwsproxy.ui.theme.Accent
import com.tgwsproxy.ui.theme.Border
import com.tgwsproxy.ui.theme.Destructive
import com.tgwsproxy.ui.theme.GlassBorder
import com.tgwsproxy.ui.theme.GlassShadow
import com.tgwsproxy.ui.theme.GlassSurfaceMuted
import com.tgwsproxy.ui.theme.Info
import com.tgwsproxy.ui.theme.LogSurface
import com.tgwsproxy.ui.theme.Mauve
import com.tgwsproxy.ui.theme.OnAccent
import com.tgwsproxy.ui.theme.Primary
import com.tgwsproxy.ui.theme.Signal
import com.tgwsproxy.ui.theme.Success
import com.tgwsproxy.ui.theme.Surface
import com.tgwsproxy.ui.theme.SurfaceElevated
import com.tgwsproxy.ui.theme.SurfaceVariant
import com.tgwsproxy.ui.theme.TextMuted
import com.tgwsproxy.ui.theme.TextPrimary
import com.tgwsproxy.ui.theme.TextSecondary
import com.tgwsproxy.ui.theme.TgWsProxyTheme
import com.tgwsproxy.ui.theme.Warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private fun copyToClipboard(context: Context, label: String, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }
}

private fun formatBytes(context: android.content.Context, b: Long): String {
    if (b < 1024) return context.getString(R.string.unit_b, b)
    val kb = b / 1024.0
    if (kb < 1024) return context.getString(R.string.unit_kb, kb)
    val mb = kb / 1024.0
    if (mb < 1024) return context.getString(R.string.unit_mb, mb)
    return context.getString(R.string.unit_gb, mb / 1024.0)
}

private fun formatUptime(context: android.content.Context, startedAt: Long, now: Long): String {
    if (startedAt <= 0) return context.getString(R.string.em_dash)
    val s = ((now - startedAt) / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

private fun routeLabel(context: android.content.Context, route: String): String = when (route) {
    "cloudflare" -> "Cloudflare"
    "direct" -> context.getString(R.string.route_direct)
    "tcp" -> "TCP"
    else -> context.getString(R.string.em_dash)
}

private enum class MainTab { Telegram, Sites }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ProxyViewModel = viewModel(),
    desyncVm: DesyncViewModel = viewModel(),
    onRunAutoTune: () -> Unit = { desyncVm.runAutoTune() },
    onEnableVpn: () -> Unit = {},
    onDisableVpn: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var tab by remember { mutableStateOf(MainTab.Telegram) }
    var logsExpanded by remember { mutableStateOf(false) }
    var tgAdvancedOpen by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(shouldShowOnboarding(context)) }

    // Both of these decide whether their card exists at all, and that decision has to be made out
    // here: the list spaces its items by a fixed 16dp, which an item collects even when its content
    // draws nothing, so "return early inside the composable" leaves a gap behind. Hoisting the
    // update probe also stops it re-firing every time the banner's item leaves the lazy window.
    val updateUrl = rememberUpdateUrl(context)
    val batteryRestricted = !batteryOptimizationsIgnored()

    if (showOnboarding) {
        OnboardingDialog(
            onDismiss = {
                markOnboardingShown(context)
                showOnboarding = false
            }
        )
    }

    // The whole log is one item, so "within two items of the end" would be true for the entire
    // scroll through an expanded 200-line log and every new line would yank the user back down.
    // Only the genuinely-last item being on screen counts as being at the bottom.
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    LaunchedEffect(tab) {
        listState.scrollToItem(0)
    }

    // Keyed on the newest line's sequence number, not on the log's size: the log is capped at 200,
    // so once it fills up the size stops changing and a size key would freeze the auto-follow for
    // the rest of the session. seq keeps moving for every line that arrives, cap or no cap.
    LaunchedEffect(uiState.logs.lastOrNull()?.seq) {
        if (tab != MainTab.Telegram) return@LaunchedEffect
        val totalItems = listState.layoutInfo.totalItemsCount
        if (isAtBottom && totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    JevioBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                // Was WindowInsets(0,0,0,0), which stripped the status-bar inset the bar exists to
                // apply — under the edge-to-edge Android 15 enforces, the mark and the wordmark
                // rendered underneath the clock. Scaffold hands the body a top padding equal to
                // this bar's height, so the inset has to be consumed here or the whole screen
                // shifts up by it.
                windowInsets = TopAppBarDefaults.windowInsets,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.heightIn(min = 38.dp),
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_jevio_logo),
                            // Decorative: the wordmark right beside it already says the same thing,
                            // and a description here made TalkBack read the brand twice over.
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(Primary),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Jevio",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.Light,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "UNBLOCKER",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 1.5.sp,
                                    lineHeight = 12.sp,
                                ),
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextPrimary,
                ),
            )
            },
            containerColor = Color.Transparent,
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)
                ) {
                    if (updateUrl != null) {
                        item(key = "update-banner") { UpdateBanner(context = context, url = updateUrl) }
                    }
                    item(key = "main-tabs") {
                        MainTabRow(selected = tab, onSelect = { tab = it })
                    }

                    when (tab) {
                        MainTab.Telegram -> {
                            item(key = "tg-hero") {
                                TelegramHero(
                                    uiState = uiState,
                                    onToggle = { viewModel.toggleProxy() },
                                    onOpenTelegram = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.proxyLink))
                                        context.startActivity(intent)
                                    },
                                )
                            }

                            if (batteryRestricted) {
                                item(key = "battery-optimization") { BatteryOptimizationCard() }
                            }

                            item(key = "tg-advanced") {
                                TgAdvancedCard(
                                    expanded = tgAdvancedOpen,
                                    onToggle = { tgAdvancedOpen = !tgAdvancedOpen },
                                    uiState = uiState,
                                    context = context,
                                    onRegenerateSecret = { viewModel.regenerateSecret() },
                                    onSaveFakeTls = { viewModel.setFakeTlsDomain(it) },
                                    onSaveCfDomain = { viewModel.setCfDomain(it) },
                                    onSaveCfWorkerDomain = { viewModel.setCfWorkerDomain(it) },
                                )
                            }

                            if (uiState.logs.isNotEmpty()) {
                                item(key = "logs-header") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { logsExpanded = !logsExpanded }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = stringResource(R.string.logs),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = TextSecondary
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "${uiState.logs.size}",
                                                // tnum, like every other live number here: the count
                                                // climbs to 200 and proportional digits would resize
                                                // the pill under it on almost every log line.
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFeatureSettings = "tnum"
                                                ),
                                                color = TextSecondary,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(SurfaceVariant)
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                        Icon(
                                            imageVector = if (logsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = if (logsExpanded) stringResource(R.string.hide_logs) else stringResource(R.string.show_logs),
                                            tint = TextSecondary
                                        )
                                    }
                                }

                                if (logsExpanded) {
                                    // One item, not one per line: as top-level entries the lines
                                    // inherited the list's 16dp page gap, and LogItem is drawn to
                                    // sit flush — 200 of them turned the log into a ladder that was
                                    // half empty space. The cost is that the whole (200-line capped)
                                    // log composes at once instead of only its visible window, which
                                    // is cheap next to reading a log full of holes.
                                    item(key = "logs-body") {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            uiState.logs.forEach { log -> LogItem(log) }
                                        }
                                    }
                                }
                            }

                            item(key = "tg-channel") { TelegramChannelCard(context) }
                        }

                        MainTab.Sites -> {
                            unblockSections(
                                vm = desyncVm,
                                onRunAutoTune = onRunAutoTune,
                                onEnable = onEnableVpn,
                                onDisable = onDisableVpn,
                            )
                            item(key = "tg-channel-sites") { TelegramChannelCard(context) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainTabRow(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(999.dp), ambientColor = GlassShadow, spotColor = GlassShadow)
            .clip(RoundedCornerShape(999.dp))
            .background(GlassSurfaceMuted)
            .border(1.dp, GlassBorder, RoundedCornerShape(999.dp))
            .padding(4.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            val reduceMotion = reducedMotionEnabled()
            val segmentWidth = maxWidth / 2
            val indicatorOffset by animateDpAsState(
                targetValue = if (selected == MainTab.Telegram) 0.dp else segmentWidth,
                animationSpec = tween(
                    durationMillis = if (reduceMotion) 0 else 240,
                    easing = JevioEaseOut,
                ),
                label = "mainTabIndicatorOffset",
            )

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(Signal)
                    .border(1.dp, GlassBorder, RoundedCornerShape(999.dp)),
            )

            Row(modifier = Modifier.fillMaxSize()) {
                MainTabButton(
                    label = "Telegram",
                    selected = selected == MainTab.Telegram,
                    onClick = { onSelect(MainTab.Telegram) },
                    modifier = Modifier.weight(1f),
                )
                MainTabButton(
                    label = stringResource(R.string.tab_sites),
                    selected = selected == MainTab.Sites,
                    onClick = { onSelect(MainTab.Sites) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MainTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(999.dp))
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                role = Role.Tab,
            ) {
                if (!selected) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            // The selected tab sits on the accent-filled indicator, so its label has to flip to
            // OnAccent like every other filled surface in the app. Left as TextPrimary it was cream
            // on peach — 1.6:1, and 1.0:1 back when the accent was lime, i.e. the label of whichever
            // tab you were on was the least readable text on screen.
            color = if (selected) OnAccent else TextPrimary,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- Telegram: status / controls / advanced ----------

/**
 * Minimal Telegram hero: one state dial owns start/stop; secondary actions stay subordinate.
 */
@Composable
private fun TelegramHero(
    uiState: ProxyUiState,
    onToggle: () -> Unit,
    onOpenTelegram: () -> Unit,
) {
    val running = uiState.isRunning
    val busy = uiState.isLoading
    val haptic = LocalHapticFeedback.current
    val reduceMotion = reducedMotionEnabled()
    val stateLabelStyle = if (LocalDensity.current.fontScale >= 1.5f) {
        MaterialTheme.typography.titleLarge
    } else {
        MaterialTheme.typography.headlineMedium
    }
    val stateLabel = when {
        busy && running -> stringResource(R.string.state_stopping)
        busy -> stringResource(R.string.state_starting)
        running -> stringResource(R.string.state_active)
        else -> stringResource(R.string.state_off)
    }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
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
                    text = "Telegram",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.local_proxy),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        JevioStateDial(
            active = running,
            busy = busy,
            onClick = onToggle,
            accessibilityLabel = stringResource(R.string.a11y_telegram_proxy),
            onClickLabel = if (running) stringResource(R.string.a11y_stop_proxy) else stringResource(R.string.a11y_start_proxy),
            announcedState = when {
                busy && running -> stringResource(R.string.a11y_turning_off)
                busy -> stringResource(R.string.a11y_turning_on)
                running -> stringResource(R.string.state_on)
                else -> stringResource(R.string.state_off)
            },
        )
        Spacer(Modifier.height(18.dp))

        Crossfade(
            targetState = stateLabel,
            animationSpec = tween(if (reduceMotion) 0 else 220, easing = JevioEaseOut),
            label = "telegramStateLabel",
            modifier = Modifier.fillMaxWidth(),
        ) { label ->
            Text(
                text = label,
                color = if (running && !busy) Success else TextPrimary,
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
            text = when {
                running && uiState.route.isNotEmpty() ->
                    stringResource(R.string.route_label, routeLabel(LocalContext.current, uiState.route))
                running -> stringResource(R.string.local_proxy_for_telegram)
                else -> stringResource(R.string.one_tap_telegram)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // A failed start has to say so under the dial, or it is indistinguishable from never having
        // pressed it. Shared with the Sites hero so the two cannot drift again — the comment here used
        // to claim «same treatment» while only one of them had the guards.
        JevioHeroError(error = uiState.error, running = running)

        if (running && uiState.proxyLink.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            PillButton(
                label = stringResource(R.string.connect_telegram),
                loading = false,
                destructive = false,
                enabled = true,
                outlined = true,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenTelegram()
                },
            )
        }

        AnimatedVisibility(
            visible = running,
            enter = fadeIn(tween(if (reduceMotion) 0 else 220, easing = JevioEaseOut)) +
                expandVertically(tween(if (reduceMotion) 0 else 220, easing = JevioEaseOut)),
            exit = fadeOut(tween(if (reduceMotion) 0 else 140)) +
                shrinkVertically(tween(if (reduceMotion) 0 else 140)),
        ) {
            Column {
                Spacer(Modifier.height(18.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(GlassSurfaceMuted)
                        .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MiniStat(
                            label = stringResource(R.string.stat_uptime),
                            value = formatUptime(LocalContext.current, uiState.startedAt, now),
                            modifier = Modifier.weight(1f),
                        )
                        MiniStat(
                            label = stringResource(R.string.stat_connections),
                            value = uiState.connectionCount.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MiniStat(
                            label = stringResource(R.string.stat_up),
                            value = formatBytes(LocalContext.current, uiState.bytesUp),
                            modifier = Modifier.weight(1f),
                        )
                        MiniStat(
                            label = stringResource(R.string.stat_down),
                            value = formatBytes(LocalContext.current, uiState.bytesDown),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Full-width fully-rounded (pill) primary/secondary button. */
@Composable
private fun PillButton(
    label: String,
    loading: Boolean,
    destructive: Boolean,
    enabled: Boolean,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    val reduceMotion = reducedMotionEnabled()
    val bg = when {
        outlined -> GlassSurfaceMuted
        destructive -> GlassSurfaceMuted
        !enabled -> SurfaceVariant
        else -> Accent
    }
    val fg = when {
        destructive -> Destructive
        outlined -> TextPrimary
        !enabled || loading -> TextMuted
        else -> OnAccent
    }
    val border = when {
        destructive -> BorderStroke(1.5.dp, Destructive)
        outlined -> BorderStroke(1.dp, Primary.copy(alpha = 0.22f))
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .shadow(
                elevation = if (!outlined && !destructive && enabled) 9.dp else 0.dp,
                shape = shape,
                ambientColor = GlassShadow,
                spotColor = GlassShadow,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(bg)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled && !loading,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = loading,
            animationSpec = tween(if (reduceMotion) 0 else 180, easing = JevioEaseOut),
            label = "telegramButtonContent",
        ) { showingProgress ->
            if (showingProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = fg,
                )
            } else {
                JevioButtonLabel(text = label, color = fg)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Сворачиваемый блок Telegram-Advanced: сервер/секрет, Fake TLS, свой Cloudflare.
 */
@Composable
private fun TgAdvancedCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    uiState: ProxyUiState,
    context: Context,
    onRegenerateSecret: () -> Unit,
    onSaveFakeTls: (String) -> Unit,
    onSaveCfDomain: (String) -> Unit,
    onSaveCfWorkerDomain: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                Text(stringResource(R.string.settings), color = TextPrimary, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.tg_settings_subtitle),
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null, tint = TextSecondary, modifier = Modifier.size(22.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ProxyInfoCard(
                    uiState = uiState,
                    context = context,
                    onRegenerateSecret = onRegenerateSecret
                )
                FakeTlsCard(uiState, onSave = onSaveFakeTls)
                SettingsCard(
                    uiState,
                    onSaveCfDomain = onSaveCfDomain,
                    onSaveCfWorkerDomain = onSaveCfWorkerDomain,
                )
            }
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
                label = stringResource(R.string.server),
                value = "${uiState.host}:${uiState.port}",
                onCopy = {
                    copyToClipboard(
                        context,
                        context.getString(R.string.server),
                        "${uiState.host}:${uiState.port}",
                        context.getString(R.string.copied),
                    )
                }
            )

            HorizontalDivider(color = Border)

            Column {
                Text(
                    text = stringResource(R.string.secret),
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
                            uiState.secret.ifEmpty { stringResource(R.string.em_dash) }
                        } else {
                            "•".repeat(minOf(uiState.secret.length, 20))
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The value is the only thing here that wants the leftover width. It used to
                        // share it 50/50 with a weighted Spacer, which left ~68dp of a 360dp screen
                        // for a 173dp secret — truncated for everyone, always. The icon buttons are
                        // unweighted, so Row still measures them at full size before this gets the
                        // remainder, and short values still leave them pinned to the right edge.
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.secret.isNotEmpty()) {
                        IconButton(onClick = { secretRevealed = !secretRevealed }) {
                            Icon(
                                imageVector = if (secretRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (secretRevealed) stringResource(R.string.hide) else stringResource(R.string.show),
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = {
                            copyToClipboard(context, context.getString(R.string.secret), uiState.secret, context.getString(R.string.secret_copied))
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.copy_secret),
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (!uiState.isRunning) {
                            IconButton(onClick = onRegenerateSecret) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.regenerate_secret),
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
                        stringResource(R.string.secret_fake_tls_mode, uiState.fakeTlsDomain)
                    } else if (uiState.isRunning) {
                        stringResource(R.string.secret_persistent)
                    } else {
                        stringResource(R.string.secret_saved_hint)
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
                contentDescription = stringResource(R.string.copy),
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
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.fake_tls_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (enabled) stringResource(R.string.on_with_value, uiState.fakeTlsDomain) else stringResource(R.string.off),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (enabled) Primary else TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                        tint = TextSecondary
                    )
                }
            }

            Text(
                text = stringResource(R.string.fake_tls_desc),
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
                    Text(stringResource(R.string.fake_tls_quick_pick), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { p ->
                            val sel = domainInput.trim() == p
                            Text(
                                text = p,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sel) OnAccent else TextPrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) Accent else SurfaceVariant)
                                    .clickable { domainInput = p }
                                    .heightIn(min = 44.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text(stringResource(R.string.fake_tls_domain_label), color = TextSecondary) },
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSave(domainInput.trim()) },
                            enabled = domainInput.trim() != uiState.fakeTlsDomain,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.enable),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (enabled) {
                            OutlinedButton(
                                onClick = { domainInput = ""; onSave("") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                border = BorderStroke(1.dp, Border)
                            ) {
                                Text(
                                    stringResource(R.string.disable),
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.fake_tls_relink_hint),
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
                        text = stringResource(R.string.cf_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = stringResource(R.string.cf_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = domainInput,
                onValueChange = { domainInput = it },
                label = { Text(stringResource(R.string.cf_domains_label), color = TextSecondary) },
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
                text = stringResource(R.string.cf_domains_hint),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onSaveCfDomain(domainInput.trim()) },
                enabled = domainInput.trim() != uiState.cfDomain,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.cf_worker_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = workerInput,
                onValueChange = { workerInput = it },
                label = { Text(stringResource(R.string.cf_worker_label), color = TextSecondary) },
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
                text = stringResource(R.string.cf_worker_hint),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onSaveCfWorkerDomain(workerInput.trim()) },
                enabled = workerInput.trim() != uiState.cfWorkerDomain,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save_worker), fontWeight = FontWeight.SemiBold)
            }
            if (uiState.isRunning) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.restart_proxy_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = Warning
                )
            }
        }
    }
}

/**
 * Colour per log role. Only a lookup: the keyword matching that decides the role happens once, when
 * the line is created, because the whole 200-line log recomposes on every arriving line and seven
 * case-insensitive scans × 200 lines per line was ~1400 substring searches for one new entry.
 */
private fun logColor(kind: LogKind): Color = when (kind) {
    LogKind.ERROR -> Destructive
    LogKind.WARNING -> Warning
    LogKind.HANDSHAKE -> Mauve
    LogKind.FAKE_TLS -> Primary
    LogKind.CLOUDFLARE -> Info
    LogKind.WS -> Info
    LogKind.PLAIN -> TextSecondary
}

@Composable
private fun LogItem(log: LogLine) {
    Text(
        text = log.text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp
        ),
        color = logColor(log.kind),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(LogSurface)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
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
                    text = stringResource(R.string.tg_channel_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.tg_channel_subtitle),
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

/**
 * Whether we are already exempt from battery optimisation — re-read on every resume, since the user
 * grants it in Settings and comes back. Kept apart from the card for the same reason as
 * [rememberUpdateUrl]: the caller has to know before it decides to emit a list item.
 */
@Composable
private fun batteryOptimizationsIgnored(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun isIgnoring(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    var ignoring by remember { mutableStateOf(isIgnoring()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ignoring = isIgnoring()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return ignoring
}

/** Keeps long-running proxy services alive when Android applies background limits. */
@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        // 0.7, not 0.5: Warning was darkened to #B68A1A to separate the status colours from the
        // brand accent in tone, which took this border to 2.33:1 over the card — under the 3:1
        // floor for a component boundary, on the one card whose whole job is to not look like a
        // normal action. 0.7 restores 3.33:1 with headroom for the ambient blob behind the panel.
        border = BorderStroke(1.dp, Warning.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = Warning)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.battery_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.battery_warning_text),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    } catch (_: Exception) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Warning,
                    contentColor = OnAccent,
                ),
            ) {
                Text(
                    text = stringResource(R.string.battery_warning_button),
                    color = OnAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private const val PREFS_ONBOARDING = "jevio_onboarding"
private const val KEY_ONBOARDING_SHOWN = "onboarding_shown"

private fun shouldShowOnboarding(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_ONBOARDING, Context.MODE_PRIVATE)
    return !prefs.getBoolean(KEY_ONBOARDING_SHOWN, false)
}

private fun markOnboardingShown(context: Context) {
    context.getSharedPreferences(PREFS_ONBOARDING, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ONBOARDING_SHOWN, true)
        .apply()
}

@Composable
private fun OnboardingDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                stringResource(R.string.onboarding_welcome),
                color = TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(18.dp))
            OnboardingRow(
                icon = Icons.Default.Send,
                title = stringResource(R.string.onboarding_proxy_title),
                body = stringResource(R.string.onboarding_proxy_body),
            )
            Spacer(Modifier.height(14.dp))
            OnboardingRow(
                icon = Icons.Default.PlayArrow,
                title = stringResource(R.string.onboarding_sites_title),
                body = stringResource(R.string.onboarding_sites_body),
            )
            Spacer(Modifier.height(14.dp))
            OnboardingRow(
                icon = Icons.Default.Shield,
                title = stringResource(R.string.onboarding_control_title),
                body = stringResource(R.string.onboarding_control_body),
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = OnAccent,
                ),
            ) {
                Text(stringResource(R.string.onboarding_cta), color = OnAccent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OnboardingRow(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(body, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Release URL when GitHub reports a newer tag than ours, null otherwise — i.e. almost always. The
 * caller uses it to decide whether the banner's list item exists at all; see [MainScreen].
 */
@Composable
private fun rememberUpdateUrl(context: Context): String? {
    var updateUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        updateUrl = withContext(Dispatchers.IO) { checkForUpdate(context) }
    }
    return updateUrl
}

@Composable
private fun UpdateBanner(context: Context, url: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
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
            Text(stringResource(R.string.update_available), color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.update_tap_download),
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

@Preview(
    name = "Telegram — выключен",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
    backgroundColor = 0xFFF7F4EF,
)
@Composable
private fun TelegramStoppedPreview() {
    TelegramPreviewContent(
        uiState = ProxyUiState(
            isLoading = false,
            isRunning = false,
            secret = "dd0123456789abcdef",
        )
    )
}

@Preview(
    name = "Telegram — работает",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
    backgroundColor = 0xFFF7F4EF,
)
@Composable
private fun TelegramRunningPreview() {
    TelegramPreviewContent(
        uiState = ProxyUiState(
            isLoading = false,
            isRunning = true,
            secret = "dd0123456789abcdef",
            connectionCount = 2,
            proxyLink = "tg://proxy?server=127.0.0.1&port=1443",
            bytesUp = 2_450_000,
            bytesDown = 18_760_000,
            startedAt = System.currentTimeMillis() - 7 * 60 * 1000,
            route = "direct",
        )
    )
}

@Composable
private fun TelegramPreviewContent(uiState: ProxyUiState) {
    TgWsProxyTheme {
        JevioBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PreviewBrandHeader()
                MainTabRow(selected = MainTab.Telegram, onSelect = {})
                TelegramHero(
                    uiState = uiState,
                    onToggle = {},
                    onOpenTelegram = {},
                )
            }
        }
    }
}

@Preview(
    name = "QA — Telegram — 320dp — шрифт 1.35×",
    widthDp = 320,
    heightDp = 900,
    fontScale = 1.35f,
    showBackground = true,
    backgroundColor = 0xFFECE9E3,
)
@Composable
private fun TelegramNarrowLargeTextPreview() {
    TelegramPreviewContent(
        uiState = ProxyUiState(
            isLoading = false,
            isRunning = true,
            secret = "dd0123456789abcdef",
            connectionCount = 128,
            proxyLink = "tg://proxy?server=127.0.0.1&port=1443",
            bytesUp = 987_654_321,
            bytesDown = 9_876_543_210,
            startedAt = System.currentTimeMillis() - 27 * 60 * 60 * 1000,
            route = "cloudflare",
        )
    )
}

@Composable
private fun PreviewBrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 38.dp),
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_jevio_logo),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Primary),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "Jevio",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "UNBLOCKER",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.5.sp,
                    lineHeight = 12.sp,
                ),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A scrollable Preview of the actual main composition in the latest app.
 * Runtime state normally comes from two ViewModels, so Preview uses fixed sample states.
 */
@Preview(
    name = "Полный экран — текущий интерфейс",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
    backgroundColor = 0xFFF7F4EF,
)
@Composable
private fun FullMainScreenPreview() {
    val proxyState = ProxyUiState(
        isLoading = false,
        secret = "dd0123456789abcdef",
    )
    TgWsProxyTheme {
        JevioBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PreviewBrandHeader()
                MainTabRow(selected = MainTab.Telegram, onSelect = {})
                TelegramHero(
                    uiState = proxyState,
                    onToggle = {},
                    onOpenTelegram = {},
                )
                TgAdvancedCard(
                    expanded = false,
                    onToggle = {},
                    uiState = proxyState,
                    context = LocalContext.current,
                    onRegenerateSecret = {},
                    onSaveFakeTls = {},
                    onSaveCfDomain = {},
                    onSaveCfWorkerDomain = {},
                )
                Text(
                    stringResource(R.string.sites_unlock_heading),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 10.dp),
                )
                FullUnblockPreviewContent()
            }
        }
    }
}
