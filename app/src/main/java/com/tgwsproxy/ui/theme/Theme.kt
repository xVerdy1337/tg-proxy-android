package com.tgwsproxy.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    secondary = Primary,
    onSecondary = Cream,
    tertiary = PrimaryLight,
    onTertiary = OnAccent,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = Destructive,
    onError = OnAccent,
    errorContainer = ErrorContainer,
    outline = Border
)

@Composable
fun TgWsProxyTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Only the icon appearance is set here. statusBarColor/navigationBarColor used to be
            // painted to the canvas colour too, but both are deprecated and are a no-op on API 35 —
            // under edge-to-edge the bars are transparent by design and the app's own background
            // shows through them, which is why JevioBackground fills the whole window. Light icons
            // because that background is #11120F; MainActivity.enableEdgeToEdge asks for the same,
            // and this keeps it true across recompositions and theme changes.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
