package com.example.familysafety.ui.theme

import androidx.compose.ui.graphics.Color

// Surfaces (OLED-optimized, pine-tinted for depth — matches the app icon's ground color)
val Black         = Color(0xFF000000)
val Surface0      = Color(0xFF101A15)   // default background
val Surface1      = Color(0xFF16231C)   // card background
val Surface2      = Color(0xFF1D2C24)   // elevated surfaces

// Light surfaces (unchanged)
val SurfaceLight0 = Color(0xFFF4F7FB)
val SurfaceLight1 = Color(0xFFFFFFFF)
val SurfaceLight2 = Color(0xFFEAF0F7)

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
// SurfaceLight1 (pure white) and clear these numbers with room to spare.
val SuccessGreen  = Color(0xFF35B378)   // healthy/connected/protected states — distinct from AmberWarning on purpose
val AmberWarning  = Color(0xFFE2B45F)   // warnings, relay state
val RedDanger     = Color(0xFFFF5B66)   // genuine danger only

// Light, text (>= 4.5:1)
val SuccessTextLight = Color(0xFF1B7E4D)   // 4.72:1
// Amber is the awkward one: brown is just dark yellow, so hitting the text bar by dropping
// lightness alone turns it to mud. These keep the blue channel at zero — full chroma at the
// luminance the bar demands — which reads as burnt amber rather than brown.
val WarningTextLight = Color(0xFFA36000)   // 4.62:1
val DangerTextLight  = Color(0xFFC32B36)   // 5.25:1

// Light, indicators — dots, bars, icon tints (>= 3:1)
val SuccessIndicatorLight = Color(0xFF219A5E)  // 3.34:1
val WarningIndicatorLight = Color(0xFFC77800)  // 3.19:1
val DangerIndicatorLight  = Color(0xFFE03E4A)  // 3.94:1

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

val SurfaceDimLight              = Color(0xFFDDE3EA)
val SurfaceBrightLight           = Color(0xFFFFFFFF)
val SurfaceContainerLowestLight  = Color(0xFFFFFFFF)
val SurfaceContainerLowLight     = Color(0xFFF7F9FC)
val SurfaceContainerLight        = Color(0xFFF1F5F9)
val SurfaceContainerHighLight    = Color(0xFFEAF0F7)
val SurfaceContainerHighestLight = Color(0xFFE3EBF3)

// Inverse pair, used by snackbars.
val InverseSurfaceDark    = Color(0xFFE8EDE9)
val InverseOnSurfaceDark  = Color(0xFF16231C)
val InverseSurfaceLight   = Color(0xFF1D2C24)
val InverseOnSurfaceLight = Color(0xFFF4F7FB)

// Text
val TextPrimary   = Color(0xFFF3F5FA)
val TextSecondary = Color(0xFF95A0B3)
val TextDisabled  = Color(0xFF4A5468)

val TextPrimaryLight   = Color(0xFF182131)
val TextSecondaryLight = Color(0xFF5F6B7A)
val TextDisabledLight   = Color(0xFF9AA6B4)

// Borders / subtle chrome
val OutlineMuted  = Color(0xFF263042)
val OutlineSoft   = Color(0xFF1E2633)

val OutlineMutedLight = Color(0xFFD2DAE4)
val OutlineSoftLight  = Color(0xFFE2E8F0)

// Semantic aliases (reference constants above, no duplicate hex)
val ColorSuccess  = SuccessGreen
val ColorAlert    = AmberWarning
val ColorError    = RedDanger
