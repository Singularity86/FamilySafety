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

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Bold,
        fontSize      = 28.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Normal,
        fontSize      = 15.sp,
        letterSpacing = 0.15.sp
    ),
    labelSmall = TextStyle(
        fontFamily    = InterPlaceholder,
        fontWeight    = FontWeight.Medium,
        fontSize      = 11.sp,
        letterSpacing = 0.4.sp
    )
)
