# Design System Master File — Jevio Unblocker

> Light minimal UI. One primary CTA per screen. Progressive disclosure for power settings.

**Project:** Jevio Unblocker
**Style:** Minimalism / Swiss light
**Updated:** 2026-07-19

---

## Color Palette

| Role | Hex | Notes |
|------|-----|--------|
| Background | `#F7F4EF` | Warm beige-white |
| Surface | `#FFFFFF` | Cards |
| Surface variant | `#EFEAE3` | Chips / segment track |
| Border | `#E8E2D9` | Hairline borders only |
| Text primary | `#1A1814` | Near-black ink |
| Text secondary | `#6B6560` | Warm grey |
| Text muted | `#9A948C` | Hints |
| Accent / CTA | `#1A1814` | Solid black button |
| On accent | `#F7F4EF` | Label on filled CTA |
| Success | `#2F7D4A` | Connected / ok |
| Warning | `#B8860B` | Battery / caution |
| Destructive | `#C23B3B` | Stop / outline stop |

**Logo:** monochrome ink `#1A1814` — no gradients.

## Typography

- System sans (Material default / Roboto)
- Display status: ~34sp Bold
- Primary CTA label: titleMedium Bold
- Body: 14–16sp, secondary for helpers

## Layout

- Horizontal padding: 20dp
- Segmented control: Telegram | Сайты
- Each tab: status headline → one big CTA → secondary actions → collapsed «Настройки»
- Radius: 12–16dp
- No gradients, no pulse glows, no heavy shadows

## Components

### Primary button
- Height 58dp, full width, radius 16
- Fill: Accent; label: OnAccent
- Stop state: transparent + destructive border + destructive text

### Secondary button
- Outline or SurfaceVariant fill
- Used for «Подключить Telegram», «Автоподбор»

### Settings row
- Collapsed by default
- Surface + border, chevron
- Contains all power-user controls

## Anti-patterns

- ❌ Multiple equal-weight CTAs on the home surface
- ❌ Gradient logos / gradient CTAs
- ❌ Dark purple “hero glow” cards
- ❌ Advanced byedpi/Fake TLS on the main scroll without collapse
