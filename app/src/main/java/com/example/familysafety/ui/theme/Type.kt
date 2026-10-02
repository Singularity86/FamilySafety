package com.example.familysafety.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.familysafety.R

// Three faces, each with one job. All are SIL Open Font License and bundled in res/font
// rather than fetched through Android's downloadable-fonts provider, which goes through
// Google Play services: the app makes no network request for its own type and renders the
// same offline. Licences ship in assets/licenses.

/**
 * Bitter, a sturdy slab serif with a hand-painted-sign feel. Headings only — display,
 * headline and titleLarge (screen titles) — never running text.
 */
val DisplayFamily = FontFamily(
    Font(R.font.bitter_semibold, FontWeight.SemiBold),
    Font(R.font.bitter_bold, FontWeight.Bold)
)

/**
 * Atkinson Hyperlegible Next, from the Braille Institute: letterforms that are easy to
 * confuse (I l 1, O 0) are drawn to stay distinct for low-vision readers. Everything that
 * is read or tapped. Medium exists because labelLarge, labelMedium and titleSmall use it.
 */
val BodyFamily = FontFamily(
    Font(R.font.atkinson_next_regular, FontWeight.Normal),
    Font(R.font.atkinson_next_medium, FontWeight.Medium),
    Font(R.font.atkinson_next_semibold, FontWeight.SemiBold),
    Font(R.font.atkinson_next_bold, FontWeight.Bold)
)

/** Atkinson Hyperlegible Mono: times, distances and coordinates, so digits line up. */
val NumericFamily = FontFamily(
    Font(R.font.atkinson_mono_regular, FontWeight.Normal),
    Font(R.font.atkinson_mono_semibold, FontWeight.SemiBold)
)

// Full Material 3 role set, compressed from the two fixed anchors (bodyMedium=15sp,
// labelSmall=11sp) using M3's own size tiering (titleSmall/bodyMedium/labelLarge share
// a tier; bodySmall/labelMedium share the next one down) so every role composables
// actually call (titleMedium, bodyLarge, labelLarge, ...) picks up the app's scale
// instead of silently falling back to M3's much larger defaults.
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily    = DisplayFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 28.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily    = DisplayFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 25.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily    = DisplayFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 23.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily    = DisplayFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 21.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily    = DisplayFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 20.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily    = DisplayFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 19.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily    = DisplayFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 18.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily    = BodyFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 16.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily    = BodyFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 15.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily    = BodyFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 16.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily    = BodyFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 15.sp,
        letterSpacing = 0.15.sp
    ),
    bodySmall = TextStyle(
        fontFamily    = BodyFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 13.sp,
        letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily    = BodyFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 15.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily    = BodyFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 13.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily    = BodyFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 11.sp,
        letterSpacing = 0.4.sp
    )
)
