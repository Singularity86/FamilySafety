package com.example.familysafety.ui.theme

import androidx.compose.ui.graphics.Color

// Surfaces (OLED-optimized, pine-tinted for depth — matches the app icon's ground color)
val Black         = Color(0xFF000000)
val Surface0      = Color(0xFF101A15)   // default background
val Surface1      = Color(0xFF16231C)   // card background
val Surface2      = Color(0xFF1D2C24)   // elevated surfaces

// Light surfaces: the same pine, thinned to a whitewash.
//
// Every neutral in this file — light grounds, text, borders, in both themes — leans toward
// the pine of the dark ground. They used to be Tailwind's "slate" greys (cool blue-grey;
// the light border was slate-200 exactly), which put a cold office grey under a pine and
// amber app and is the most recognisable default look of generated UIs.
val SurfaceLight0 = Color(0xFFF2F5F0)
val SurfaceLight1 = Color(0xFFFFFFFF)
val SurfaceLight2 = Color(0xFFE7EDE6)

// Accent
val PorchAmber    = Color(0xFFE8C858)   // primary actions, brand accent — softened toward gold (was #E9A23C, hue 35° read as orange)
val PorchAmberOnLight = Color(0xFF8A6A0F) // PorchAmber darkened for light-theme foreground use — raw PorchAmber is ~1.6:1 on white, fails WCAG AA
val White         = Color(0xFFFFFFFF)

// Status signals.
//
// On the dark ground one value per signal clears both accessibility bars at once, so these
// three serve text and indicators alike. Light is not so lucky: WCAG asks 4.5:1 of text but
// only 3:1 of non-text, and a colour dark enough for 11sp label text reads as mud on an 8dp
// dot. So light splits each signal in two. Contrast below is quoted against SurfaceLight0,
// the darker of the two light grounds and therefore the binding one — cards sit on
// SurfaceLight1 (pure white) and clear these numbers with room to spare. Re-check these
// whenever SurfaceLight0 changes.
val SuccessGreen  = Color(0xFF35B378)   // healthy/connected/protected states — distinct from AmberWarning on purpose
val AmberWarning  = Color(0xFFE2B45F)   // warnings, relay state
val RedDanger     = Color(0xFFFF5B66)   // genuine danger only

// Light, text (>= 4.5:1)
val SuccessTextLight = Color(0xFF1B7E4D)   // 4.61:1
// Amber is the awkward one: brown is just dark yellow, so hitting the text bar by dropping
// lightness alone turns it to mud. These keep the blue channel at zero — full chroma at the
// luminance the bar demands — which reads as burnt amber rather than brown.
val WarningTextLight = Color(0xFFA36000)   // 4.51:1
val DangerTextLight  = Color(0xFFC32B36)   // 5.13:1

// Light, indicators — dots, bars, icon tints (>= 3:1)
val SuccessIndicatorLight = Color(0xFF219A5E)  // 3.26:1
val WarningIndicatorLight = Color(0xFFC77800)  // 3.12:1
val DangerIndicatorLight  = Color(0xFFE03E4A)  // 3.85:1

// Tonal containers.
//
// Material fills every role its scheme builder is not given, and its defaults are the
// baseline violet — which is why undefined roles showed up as lavender chips on a pine
// and gold app. Everything the scheme accepts is now named here, derived from the
// palette above, so nothing falls back.
val PrimaryContainerDark     = Color(0xFF3A3118)
val OnPrimaryContainerDark   = Color(0xFFF6E4AE)
val SecondaryContainerDark   = Color(0xFF33301E)
val OnSecondaryContainerDark = Color(0xFFEFE0B4)
val TertiaryContainerDark    = Color(0xFF1E3227)
val OnTertiaryContainerDark  = Color(0xFFC6E7D3)
val ErrorContainerDark       = Color(0xFF4A1F22)
val OnErrorContainerDark     = Color(0xFFFFD9DC)

val PrimaryContainerLight     = Color(0xFFF7E9C2)
val OnPrimaryContainerLight   = Color(0xFF3D2E00)
val SecondaryContainerLight   = Color(0xFFF5E8CC)
val OnSecondaryContainerLight = Color(0xFF3D2E05)
val TertiaryContainerLight    = Color(0xFFDCEDE3)
val OnTertiaryContainerLight  = Color(0xFF16301F)
val ErrorContainerLight       = Color(0xFFFCE6E7)
val OnErrorContainerLight     = Color(0xFF5A1015)

// Surface tiers. Material 3 draws navigation bars, sheets and menus from these; left
// undefined they come back as Material's neutral greys, which read cold against pine.
val SurfaceDimDark              = Color(0xFF0C1410)
val SurfaceBrightDark           = Color(0xFF26332B)
val SurfaceContainerLowestDark  = Color(0xFF0B120E)
val SurfaceContainerLowDark     = Color(0xFF131E18)
val SurfaceContainerDark        = Color(0xFF16231C)
val SurfaceContainerHighDark    = Color(0xFF1D2C24)
val SurfaceContainerHighestDark = Color(0xFF24352C)

val SurfaceDimLight              = Color(0xFFD9E0D8)
val SurfaceBrightLight           = Color(0xFFFFFFFF)
val SurfaceContainerLowestLight  = Color(0xFFFFFFFF)
val SurfaceContainerLowLight     = Color(0xFFF7F9F6)
val SurfaceContainerLight        = Color(0xFFEFF3EE)
val SurfaceContainerHighLight    = Color(0xFFE7EDE6)
val SurfaceContainerHighestLight = Color(0xFFE0E7DF)

// Inverse pair, used by snackbars.
val InverseSurfaceDark    = Color(0xFFE8EDE9)
val InverseOnSurfaceDark  = Color(0xFF16231C)
val InverseSurfaceLight   = Color(0xFF1D2C24)
val InverseOnSurfaceLight = Color(0xFFF2F5F0)

// Text. Contrast quoted against the card surface (Surface1 / SurfaceLight0).
val TextPrimary   = Color(0xFFEEF2EC)   // 14.4:1
val TextSecondary = Color(0xFFA3B2A8)   // 7.3:1
val TextDisabled  = Color(0xFF5E6E63)   // 3.0:1 — disabled text is exempt, but stays findable

val TextPrimaryLight   = Color(0xFF15231B)   // 14.8:1
val TextSecondaryLight = Color(0xFF536358)   // 5.8:1
val TextDisabledLight  = Color(0xFF97A69B)   // 2.3:1, exempt

// Borders.
//
// Two jobs, two strengths. Material draws `outline` (OutlineMuted) as the border of text
// fields and outlined buttons — the only thing that shows where a control is, so it has to
// clear WCAG's 3:1 for control boundaries on every surface. Both old values sat near 1.3:1.
// `outlineVariant` (OutlineSoft) is the hairline on cards and dividers, which separates
// things that are already distinct and is meant to stay quiet.
val OutlineMuted  = Color(0xFF64796B)   // ≥ 3.1:1 on Surface0–2
val OutlineSoft   = Color(0xFF2C3D33)

val OutlineMutedLight = Color(0xFF7A8C7F)   // ≥ 3.0:1 on SurfaceLight0–2
val OutlineSoftLight  = Color(0xFFD3DDD4)

// Semantic aliases (reference constants above, no duplicate hex)
val ColorSuccess  = SuccessGreen
val ColorAlert    = AmberWarning
val ColorError    = RedDanger
