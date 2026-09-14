package com.example.familysafety.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The three status signals, split by what they are drawn as.
 *
 * `*Text` is for anything a screen reader would read as words; `*Indicator` is for dots,
 * progress bars and icon tints. They differ only on the light ground, where the two WCAG
 * bars (4.5:1 for text, 3:1 for non-text) pull far enough apart that one value cannot serve
 * both without either failing the text or muddying the dot. Reach for `*Indicator` whenever
 * the colour is not carrying the message on its own.
 */
@Immutable
data class StatusPalette(
    val successText: Color,
    val successIndicator: Color,
    val warningText: Color,
    val warningIndicator: Color,
    val dangerText: Color,
    val dangerIndicator: Color
)

internal val DarkStatusPalette = StatusPalette(
    successText      = SuccessGreen,
    successIndicator = SuccessGreen,
    warningText      = AmberWarning,
    warningIndicator = AmberWarning,
    dangerText       = RedDanger,
    dangerIndicator  = RedDanger
)

internal val LightStatusPalette = StatusPalette(
    successText      = SuccessTextLight,
    successIndicator = SuccessIndicatorLight,
    warningText      = WarningTextLight,
    warningIndicator = WarningIndicatorLight,
    dangerText       = DangerTextLight,
    dangerIndicator  = DangerIndicatorLight
)

internal val LocalStatusPalette = staticCompositionLocalOf { DarkStatusPalette }

/** Status colours for the active theme. Provided by [AppTheme]. */
val statusColors: StatusPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusPalette.current
