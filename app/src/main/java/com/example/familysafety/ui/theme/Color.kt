package com.example.familysafety.ui.theme

import androidx.compose.ui.graphics.Color

// Surfaces (OLED-optimized, slightly tinted for depth)
val Black         = Color(0xFF000000)
val Surface0      = Color(0xFF070A0F)   // default background
val Surface1      = Color(0xFF101520)   // card background
val Surface2      = Color(0xFF182131)   // elevated surfaces

// Accent
val TealPrimary   = Color(0xFF13D0B0)   // primary actions
val AmberWarning  = Color(0xFFE2B45F)   // warnings, relay state
val RedDanger     = Color(0xFFFF5B66)   // genuine danger only

// Text
val TextPrimary   = Color(0xFFF3F5FA)
val TextSecondary = Color(0xFF95A0B3)
val TextDisabled  = Color(0xFF4A5468)

// Borders / subtle chrome
val OutlineMuted  = Color(0xFF263042)
val OutlineSoft   = Color(0xFF1E2633)

// Semantic aliases (reference constants above, no duplicate hex)
val ColorSuccess  = TealPrimary
val ColorAlert    = AmberWarning
val ColorError    = RedDanger
