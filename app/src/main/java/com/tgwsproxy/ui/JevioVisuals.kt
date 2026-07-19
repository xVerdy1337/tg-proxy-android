package com.tgwsproxy.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tgwsproxy.ui.theme.Background
import com.tgwsproxy.ui.theme.GlassBorder
import com.tgwsproxy.ui.theme.GlassShadow
import com.tgwsproxy.ui.theme.GlassSurface
import com.tgwsproxy.ui.theme.Primary
import com.tgwsproxy.ui.theme.Signal
import com.tgwsproxy.ui.theme.SurfaceElevated

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
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.PowerSettingsNew,
) {
    Box(
        modifier = modifier.size(138.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = Signal,
                trackColor = Primary.copy(alpha = 0.10f),
                strokeWidth = 8.dp,
                strokeCap = StrokeCap.Round,
            )
        } else {
            CircularProgressIndicator(
                progress = { if (active) 1f else 0f },
                modifier = Modifier.fillMaxSize(),
                color = Signal,
                trackColor = Primary.copy(alpha = 0.12f),
                strokeWidth = 8.dp,
                strokeCap = StrokeCap.Round,
            )
        }
        Box(
            modifier = Modifier
                .size(102.dp)
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
