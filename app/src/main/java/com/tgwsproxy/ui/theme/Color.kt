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
// Pressed peer of [Accent]: L* 68.4 against the accent's 78.3, so 1.35:1 — a shrink you can see
// without it reading as a second, different button, and [OnAccent] still holds 7.8:1 on top.
val AccentDark = Color(0xFFD69A6E)

// The one accent tint that carries meaning rather than decoration: the selected byedpi preset row.
// At the old 0.16 the peach lands 1.11:1 from an unselected SurfaceVariant row (ΔL* 3.0) — in a
// list of near-identical rows that is not a selection signal. 0.22 gives ΔL* 7.5 / 1.28:1, past
// what lime bought at 0.16.
val AccentSoft = Signal.copy(alpha = 0.22f)

// Peach sits at L* 78, which is where the status colours already were: against it Success measured
// 1.07:1 and Warning 1.13:1, so «on» drawn in the accent and «on» drawn in green differed by hue
// alone — nothing that survives greyscale or deuteranopia. Both drop into an L* 59-60 band, ~1.8:1
// from the accent, which is the tonal step the old lime gave for free. Legibility sets the floor,
// not taste: as ink they hold 5.8:1 on the canvas, 5.3:1 on a glass panel and 5.1:1 on the log
// sheet, and the dark label on the amber battery-optimization fill keeps 5.9:1.
//
// That band is sized for INK. A caller drawing these at reduced alpha has to pay for it on the
// alpha, not by reaching back in here: [Warning] at 0.5 over the battery-optimization card
// (Surface on Background = #1B1C18, Y 0.0112) composites to #685319, Y 0.0921 — 2.32:1, under the
// 3:1 a component boundary needs; 0.70 gives #876919, Y 0.1535, 3.33:1. Brightening the token to
// buy the same border at 0.5 needs ~#E5AB1B, which is L* 73 — 1.15:1 from the accent, i.e. exactly
// the collision this band exists to avoid, and it would drag every other Warning surface with it.
val Warning = Color(0xFFB68A1A)
val Info = Color(0xFF9BCBFF)
val Success = Color(0xFF2FA35D)
val LogSurface = Color(0xCC23251F)

/** Solid accent fill (kept as Brush for existing call sites). */
val AccentGradient: Brush
    get() = SolidColor(Accent)

/** Text/icon on filled primary buttons. */
val OnAccent = Background
