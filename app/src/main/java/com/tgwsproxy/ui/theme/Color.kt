package com.tgwsproxy.ui.theme

import androidx.compose.ui.graphics.Color

// === Jevio palette (coolors: 181621 / EBEBD3 / 463F5D / 896998 / E07A98) ===
// Deep ink background, cream text, layered purple surfaces, mauve + pink accents.

val Background = Color(0xFF181621)        // 181621 — deep ink
val Surface = Color(0xFF221E30)           // cards (between bg and 463F5D)
val SurfaceElevated = Color(0xFF2B2540)   // raised inner surfaces
val SurfaceVariant = Color(0xFF463F5D)    // 463F5D — chips / inputs

val Primary = Color(0xFFE07A98)           // E07A98 — pink (main accent)
val PrimaryDark = Color(0xFFC15F7D)
val PrimaryLight = Color(0xFFEB97AE)

val Mauve = Color(0xFF896998)             // 896998 — secondary accent
val MauveLight = Color(0xFFA585B3)

val Cream = Color(0xFFEBEBD3)             // EBEBD3 — cream highlight

val TextPrimary = Color(0xFFEBEBD3)       // cream text
val TextSecondary = Color(0xFFA89CBC)     // muted mauve-grey
val TextMuted = Color(0xFF7C7191)

val Border = Color(0xFF463F5D)
val Destructive = Color(0xFFE0617F)       // warm pink-red for stop
val ErrorContainer = Color(0xFF3A1A26)

// Semantic accents reused across the UI
val Accent = Color(0xFFE07A98)            // primary CTA / running
val AccentDark = Color(0xFFC15F7D)
val AccentSoft = Color(0xFFE07A98)        // "active" status colour
val Warning = Color(0xFFE0B36A)           // warm cream-amber
val Info = Color(0xFF896998)              // mauve (cloudflare / WS lines)
val LogSurface = Color(0xFF13111B)        // near-black log rows
