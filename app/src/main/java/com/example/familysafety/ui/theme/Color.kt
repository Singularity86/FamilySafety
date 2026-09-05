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
val PorchAmber    = Color(0xFFE9A23C)   // primary actions, brand accent — same amber as the app icon
val SuccessGreen  = Color(0xFF35B378)   // healthy/connected/protected states — distinct from AmberWarning on purpose
val AmberWarning  = Color(0xFFE2B45F)   // warnings, relay state
val RedDanger     = Color(0xFFFF5B66)   // genuine danger only

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
