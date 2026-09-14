package com.example.familysafety.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Placeholder: FontFamily.SansSerif (Roboto) stands in for Inter until Session 2
// wires up the Google Fonts provider. All sizes, weights, and letter-spacing are
// final per spec. Only the typeface changes when Inter is connected.
private val InterPlaceholder = FontFamily.SansSerif

// Full Material 3 role set, compressed from the two fixed anchors (bodyMedium=15sp,
// labelSmall=11sp) using M3's own size tiering (titleSmall/bodyMedium/labelLarge share
// a tier; bodySmall/labelMedium share the next one down) so every role composables
// actually call (titleMedium, bodyLarge, labelLarge, ...) picks up the app's scale
// instead of silently falling back to M3's much larger defaults.
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Bold,
        fontSize      = 28.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Bold,
        fontSize      = 25.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Bold,
        fontSize      = 23.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Bold,
        fontSize      = 21.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Bold,
        fontSize      = 20.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 19.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 18.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 16.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Medium,
        fontSize      = 15.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Normal,
        fontSize      = 16.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Normal,
        fontSize      = 15.sp,
        letterSpacing = 0.15.sp
    ),
    bodySmall = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Normal,
        fontSize      = 13.sp,
        letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Medium,
        fontSize      = 15.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Medium,
        fontSize      = 13.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Medium,
        fontSize      = 11.sp,
        letterSpacing = 0.4.sp
    )
)
