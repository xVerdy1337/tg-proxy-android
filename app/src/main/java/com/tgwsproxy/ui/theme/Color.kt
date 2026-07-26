package com.tgwsproxy.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

// === Jevio matte dark ===
// Deep neutral canvas, smoky charcoal surfaces and a single high-visibility accent drawn from the
// brand gradient's warm end. The alpha on surfaces preserves the existing soft-depth treatment
// without light flash.
//
// The accent has a hard constraint: [OnAccent] is the near-black canvas, because the accent is a
// FILLED button background with dark text on top. So it has to stay light. The saturated blue and
// red from the same gradient sit around 5.8:1 against that text — passable but a visible downgrade
// from the 10.2:1 the warm end gives. Swapping the accent for a darker hue means flipping OnAccent
// to a light colour first, everywhere, not just editing the value here.

val Background = Color(0xFF11120F)
val Surface = Color(0xE31C1D19)
val SurfaceElevated = Color(0xF5262722)
val SurfaceVariant = Color(0xCC33352E)

val Primary = Color(0xFFF5F3ED)
val PrimaryDark = Color(0xFFD0CCC2)
val PrimaryLight = Color(0xFFFFFFFF)

val Mauve = Color(0xFFC9C6BD)
val MauveLight = Color(0xFF96938B)

val Cream = Color(0xFF151611)

val TextPrimary = Color(0xFFF5F3ED)
val TextSecondary = Color(0xFFC4C1B8)
val TextMuted = Color(0xFF96938B)

val Border = Color(0x8067645C)
val GlassBorder = Color(0x24FFFFFF)
val GlassSurface = Color(0xE31C1D19)
val GlassSurfaceMuted = Color(0xCC2B2C27)
val GlassShadow = Color(0x60000000)
val Destructive = Color(0xFFEE7B72)
val ErrorContainer = Color(0xE83B1E1C)

val Signal = Color(0xFFEFB68C)
val Accent = Signal
val AccentDark = Color(0xFFD69A6E)
val AccentSoft = Signal
val Warning = Color(0xFFF2C94C)
val Info = Color(0xFF9BCBFF)
val Success = Color(0xFF66D090)
val LogSurface = Color(0xCC23251F)

/** Solid accent fill (kept as Brush for existing call sites). */
val AccentGradient: Brush
    get() = SolidColor(Accent)

/** Text/icon on filled primary buttons. */
val OnAccent = Background
