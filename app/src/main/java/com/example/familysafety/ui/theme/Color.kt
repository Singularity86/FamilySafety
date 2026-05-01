package com.example.familysafety.ui.theme

import androidx.compose.ui.graphics.Color

// Surfaces (OLED-optimized)
val Black         = Color(0xFF000000)
val Surface0      = Color(0xFF0A0A0F)   // default background
val Surface1      = Color(0xFF12121A)   // card background
val Surface2      = Color(0xFF1C1C28)   // elevated surfaces

// Accent
val TealPrimary   = Color(0xFF00C9A7)   // primary actions
val AmberWarning  = Color(0xFFFFB347)   // warnings, relay state
val RedDanger     = Color(0xFFFF4C4C)   // genuine danger only

// Text
val TextPrimary   = Color(0xFFF0F0F5)
val TextSecondary = Color(0xFF8A8A9A)
val TextDisabled  = Color(0xFF44445A)

// Semantic aliases (reference constants above, no duplicate hex)
val ColorSuccess  = TealPrimary
val ColorAlert    = AmberWarning
val ColorError    = RedDanger
