package com.tgwsproxy.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// === Jevio palette (coolors: 181621 / EBEBD3 / 463F5D / 896998 / E07A98) ===
// Deep ink background, cream text, layered purple surfaces, mauve + pink accents.

val Background = Color(0xFF14111F)        // 181621 — deep ink
val Surface = Color(0xFF221C30)           // cards (between bg and 463F5D)
val SurfaceElevated = Color(0xFF2B233B)   // raised inner surfaces
val SurfaceVariant = SurfaceElevated

val Primary = Color(0xFFE6789D)           // E07A98 — pink (main accent)
val PrimaryDark = Color(0xFFC95E83)
val PrimaryLight = Color(0xFFEF91AE)
val OnAccent = Color(0xFF181321)

val Mauve = Color(0xFF63C7E6)             // 896998 — secondary accent
val MauveLight = Color(0xFF8BD7ED)

val Cream = Color(0xFFF7F4FA)             // EBEBD3 — cream highlight

val TextPrimary = Color(0xFFF7F4FA)       // cream text
val TextSecondary = Color(0xFFB7AEC7)     // muted mauve-grey
val TextMuted = Color(0xFF81778F)

val Border = Color(0xFF504462)
val Destructive = Color(0xFFFF708A)       // warm pink-red for stop
val ErrorContainer = Color(0xFF3A1A26)

// Semantic accents reused across the UI
val Accent = Color(0xFFE6789D)            // primary CTA / running
val AccentDark = Color(0xFFC95E83)
val AccentSoft = Color(0xFFE6789D)        // "active" status colour
val Warning = Color(0xFFF1C96D)           // warm cream-amber
val Info = Color(0xFF63C7E6)              // mauve (cloudflare / WS lines)
val Success = Color(0xFF62D6A0)           // clear connection / completed state
val LogSurface = Color(0xFF13111B)        // near-black log rows

val UpdateSurface = Color(0xFF342432)
val UpdateBorder = Color(0xFF8F506B)

// Rose gradient for primary CTAs. Dark (Background) text keeps >=4.5:1 contrast at both ends.
val AccentGradient: Brush
    get() = Brush.horizontalGradient(listOf(PrimaryLight, PrimaryDark))
