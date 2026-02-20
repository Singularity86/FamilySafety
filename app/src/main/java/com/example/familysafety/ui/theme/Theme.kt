package com.example.familysafety.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ─── Colour schemes ──────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    primary             = DarkPrimary,
    onPrimary           = DarkOnPrimary,
    primaryContainer    = DarkPrimaryContainer,
    onPrimaryContainer  = DarkOnPrimaryContainer,
    secondary           = DarkSecondary,
    onSecondary         = DarkOnSecondary,
    secondaryContainer  = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary            = DarkTertiary,
    onTertiary          = DarkOnTertiary,
    tertiaryContainer   = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error               = DarkError,
    onError             = DarkOnError,
    errorContainer      = DarkErrorContainer,
    onErrorContainer    = DarkOnErrorContainer,
    background          = DarkBackground,
    onBackground        = DarkOnBackground,
    surface             = DarkSurface,
    onSurface           = DarkOnSurface,
    surfaceVariant      = DarkSurfaceVariant,
    onSurfaceVariant    = DarkOnSurfaceVariant,
    outline             = DarkOutline,
    surfaceTint         = DarkPrimary
)

private val LightColorScheme = lightColorScheme(
    primary             = LightPrimary,
    onPrimary           = LightOnPrimary,
    primaryContainer    = LightPrimaryContainer,
    onPrimaryContainer  = LightOnPrimaryContainer,
    secondary           = LightSecondary,
    onSecondary         = LightOnSecondary,
    secondaryContainer  = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary            = LightTertiary,
    onTertiary          = LightOnTertiary,
    tertiaryContainer   = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error               = LightError,
    onError             = LightOnError,
    errorContainer      = LightErrorContainer,
    onErrorContainer    = LightOnErrorContainer,
    background          = LightBackground,
    onBackground        = LightOnBackground,
    surface             = LightSurface,
    onSurface           = LightOnSurface,
    surfaceVariant      = LightSurfaceVariant,
    onSurfaceVariant    = LightOnSurfaceVariant,
    outline             = LightOutline,
    surfaceTint         = LightPrimary
)

// ─── Shapes — extra-rounded, clean geometry ───────────────────

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(20.dp),
    large      = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

// ─── Theme ────────────────────────────────────────────────────

@Composable
fun FamilySafetyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Always use our hand-crafted palette — no dynamic color overrides.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge: transparent status + nav bar; content draws beneath.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        shapes      = AppShapes,
        content     = content
    )
}
