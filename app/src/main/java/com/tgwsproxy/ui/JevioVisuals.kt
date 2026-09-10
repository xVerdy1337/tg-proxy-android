package com.tgwsproxy.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tgwsproxy.ui.theme.AccentDark
import com.tgwsproxy.ui.theme.Background
import com.tgwsproxy.ui.theme.Destructive
import com.tgwsproxy.ui.theme.GlassBorder
import com.tgwsproxy.ui.theme.GlassShadow
import com.tgwsproxy.ui.theme.GlassSurface
import com.tgwsproxy.ui.theme.Primary
import com.tgwsproxy.ui.theme.Signal
import com.tgwsproxy.ui.theme.TextPrimary
import com.tgwsproxy.ui.theme.SurfaceElevated

/** Shared motion curve: quick response with a soft, natural finish. */
internal val JevioEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * Honor the Android system setting that disables animator motion. Re-checked on every resume:
 * the developer setting can flip while the process is alive, and the value frozen by a bare
 * remember{} at first composition would keep stale motion behaviour for the rest of the session.
 */
@Composable
internal fun reducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun isReduced(): Boolean = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

    var reduced by remember { mutableStateOf(isReduced()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduced = isReduced()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return reduced
}

/** Stable, interruptible press feedback shared by every pill button. */
@Composable
internal fun rememberPressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = reducedMotionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.97f else 1f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else 140,
            easing = JevioEaseOut,
        ),
        label = "pressScale",
    )
    return scale
}

/** Shared centered label that may wrap without entering the rounded end caps. */
@Composable
internal fun JevioButtonLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp,
        ),
        textAlign = TextAlign.Center,
        maxLines = 2,
        softWrap = true,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The failure line under a hero dial, on both tabs.
 *
 * Shared rather than written twice, because the two copies had already drifted: what looks like one
 * line of styling carries two rules that are easy to lose, and the Sites hero lost both.
 *
 * [running] hides it. `error` on both states carries start/stop failures — which belong here, they
 * are the only thing distinguishing a failed attempt from never having pressed the dial — but
 * DesyncVpnService also routes live per-flow relay diagnostics through the same field while the VPN
 * is up. One upstream reset painted a raw «upstream SocketException: Connection reset» in alarm red
 * directly beneath a dial reading ON in green: untranslated, unlabelled, about a single flow the next
 * connection retried fine, and it stayed until another flow overwrote it. Those belong to the
 * diagnostics line inside the live-stats card, which is shown on exactly the condition this hides on.
 *
 * Blank and not merely null, for the same reason: a caller clearing a stale diagnostic may write ""
 * rather than null (TcpConnection does), and `?.let` admits the empty string — an empty Text keeps
 * the Spacer above it, so the first failed flow left a phantom 10dp gap that never went away.
 *
 * Scoped to [ColumnScope] because the leading Spacer is vertical: in a Row it would silently become
 * horizontal padding instead of the gap this is meant to open.
 */
@Composable
internal fun ColumnScope.JevioHeroError(
    error: String?,
    running: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    if (running || error.isNullOrBlank()) return
    Spacer(Modifier.height(10.dp))
    Text(
        text = error,
        style = MaterialTheme.typography.bodySmall,
        color = Destructive,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    if (onRetry != null) {
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.try_again), color = Destructive)
        }
    }
}

/** Warm ambient shapes make translucent surfaces read as matte glass without a heavy image. */
@Composable
internal fun JevioBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.background(Background)) {
        AmbientShape(
            color = Signal.copy(alpha = 0.22f),
            size = 210.dp,
            blur = 72.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 82.dp, y = 68.dp),
        )
        AmbientShape(
            color = Primary.copy(alpha = 0.10f),
            size = 260.dp,
            blur = 92.dp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-112).dp, y = 76.dp),
        )
        content()
    }
}

@Composable
private fun AmbientShape(
    color: Color,
    size: Dp,
    blur: Dp,
    modifier: Modifier = Modifier,
) {
    val softModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.blur(blur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
    } else {
        // Blur is unavailable before Android 12; keep the fallback almost imperceptible.
        Modifier.alpha(0.18f)
    }
    Box(
        modifier = modifier
            .size(size)
            .then(softModifier)
            .clip(CircleShape)
            .background(color),
    )
}

/** Shared translucent sheet used by both Telegram and site-unblocking surfaces. */
@Composable
internal fun JevioGlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    contentPadding: PaddingValues = PaddingValues(22.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = GlassShadow,
                spotColor = GlassShadow,
            )
            .clip(shape)
            .background(GlassSurface)
            .border(1.dp, GlassBorder, shape)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** Binary connection indicator inspired by the reference's circular match gauge. */
@Composable
internal fun JevioStateDial(
    active: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    accessibilityLabel: String,
    onClickLabel: String,
    announcedState: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.PowerSettingsNew,
) {
    val reduceMotion = reducedMotionEnabled()
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction)
    val pressed by interaction.collectIsPressedAsState()
    // The ring is the only accent-painted surface here and a 3% shrink on a 138dp circle is easy to
    // miss, so the press also sinks the accent to its darker peer. A colour swap is not motion, so
    // unlike the scale it still happens when animators are off — only the transition is zeroed.
    val ringColor by animateColorAsState(
        targetValue = if (pressed) AccentDark else Signal,
        animationSpec = tween(if (reduceMotion) 0 else 140, easing = JevioEaseOut),
        label = "stateRingPress",
    )
    val duration = if (reduceMotion) 0 else 260
    val ringProgress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(duration, easing = JevioEaseOut),
        label = "stateRingProgress",
    )
    val centerScale by animateFloatAsState(
        targetValue = if (active) 1f else 0.96f,
        animationSpec = tween(duration, easing = JevioEaseOut),
        label = "stateDialScale",
    )

    Box(
        modifier = modifier
            .size(138.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = !busy,
                role = Role.Button,
                onClickLabel = onClickLabel,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                stateDescription = announcedState
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = busy,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(if (reduceMotion) 0 else 180, easing = JevioEaseOut),
            label = "stateDialMode",
        ) { showingBusy ->
            if (showingBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = ringColor,
                    trackColor = Primary.copy(alpha = 0.10f),
                    strokeWidth = 8.dp,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                CircularProgressIndicator(
                    progress = { ringProgress },
                    modifier = Modifier.fillMaxSize(),
                    color = ringColor,
                    trackColor = Primary.copy(alpha = 0.12f),
                    strokeWidth = 8.dp,
                    strokeCap = StrokeCap.Round,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(102.dp)
                .graphicsLayer {
                    scaleX = centerScale
                    scaleY = centerScale
                }
                .shadow(8.dp, CircleShape, ambientColor = GlassShadow, spotColor = GlassShadow)
                .clip(CircleShape)
                .background(SurfaceElevated)
                .border(1.dp, GlassBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}
