package com.tgwsproxy.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgwsproxy.ui.theme.Background
import com.tgwsproxy.ui.theme.GlassBorder
import com.tgwsproxy.ui.theme.GlassShadow
import com.tgwsproxy.ui.theme.GlassSurface
import com.tgwsproxy.ui.theme.Primary
import com.tgwsproxy.ui.theme.Signal
import com.tgwsproxy.ui.theme.TextPrimary
import com.tgwsproxy.ui.theme.SurfaceElevated

/** Shared motion curve: quick response with a soft, natural finish. */
internal val JevioEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** Honor the Android system setting that disables animator motion. */
@Composable
internal fun reducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
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
                    color = Signal,
                    trackColor = Primary.copy(alpha = 0.10f),
                    strokeWidth = 8.dp,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                CircularProgressIndicator(
                    progress = { ringProgress },
                    modifier = Modifier.fillMaxSize(),
                    color = Signal,
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
