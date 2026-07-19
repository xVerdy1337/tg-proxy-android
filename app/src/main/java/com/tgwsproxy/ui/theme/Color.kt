package com.tgwsproxy.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

// === Jevio matte light ===
// Warm neutral canvas, smoky translucent surfaces, near-black ink and one lime accent.
// Surfaces deliberately keep alpha: the soft shapes behind them create the matte-glass depth.

val Background = Color(0xFFECE9E3)        // warm stone / beige-white
val Surface = Color(0xE8FFFFFF)           // translucent cards
val SurfaceElevated = Color(0xF5FFFFFF)   // controls inside glass
val SurfaceVariant = Color(0xB8DEDAD2)    // smoky chips / inputs

val Primary = Color(0xFF181914)           // near-black ink
val PrimaryDark = Color(0xFF090A08)
val PrimaryLight = Color(0xFF3B3C35)

val Mauve = Color(0xFF66645D)             // warm graphite
val MauveLight = Color(0xFF929087)

val Cream = Color(0xFFF7F5F0)

val TextPrimary = Color(0xFF181914)
val TextSecondary = Color(0xFF65645E)
val TextMuted = Color(0xFF8E8C84)

val Border = Color(0x80B9B5AD)
val GlassBorder = Color(0xD9FFFFFF)
val GlassSurface = Color(0xCFFFFFFF)
val GlassSurfaceMuted = Color(0xA8F6F4EF)
val GlassShadow = Color(0x16000000)
val Destructive = Color(0xFFA93232)
val ErrorContainer = Color(0xE8F7E5E3)

val Accent = Primary                      // primary CTA remains the requested black capsule
val AccentDark = PrimaryDark
val AccentSoft = Primary
val Signal = Color(0xFFDFFF4F)            // rare live / selected accent from the reference
val Warning = Color(0xFF846200)
val Info = Color(0xFF5C6B7A)
val Success = Color(0xFF2D7045)
val LogSurface = Color(0xB8DDD9D1)

/** Solid ink fill (kept as Brush for existing call sites). */
val AccentGradient: Brush
    get() = SolidColor(Accent)

/** Text/icon on filled primary buttons. */
val OnAccent = Color(0xFFF7F5F0)
