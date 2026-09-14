package com.example.familysafety.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
private val DarkColorScheme = darkColorScheme(
    primary                = PorchAmber,
    onPrimary              = Black,
    primaryContainer       = PrimaryContainerDark,
    onPrimaryContainer     = OnPrimaryContainerDark,
    inversePrimary         = PorchAmberOnLight,
    secondary              = AmberWarning,
    onSecondary            = Black,
    secondaryContainer     = SecondaryContainerDark,
    onSecondaryContainer   = OnSecondaryContainerDark,
    // Tertiary is the read-receipt tick and the in-progress pill — both healthy states,
    // so it reads from the success family rather than doubling up on the error red.
    tertiary               = SuccessGreen,
    onTertiary             = Black,
    tertiaryContainer      = TertiaryContainerDark,
    onTertiaryContainer    = OnTertiaryContainerDark,
    error                  = RedDanger,
    onError                = TextPrimary,
    errorContainer         = ErrorContainerDark,
    onErrorContainer       = OnErrorContainerDark,
    background             = Surface0,
    onBackground           = TextPrimary,
    surface                = Surface1,
    onSurface              = TextPrimary,
    surfaceVariant         = Surface2,
    onSurfaceVariant       = TextSecondary,
    surfaceTint            = PorchAmber,
    inverseSurface         = InverseSurfaceDark,
    inverseOnSurface       = InverseOnSurfaceDark,
    outline                = OutlineMuted,
    outlineVariant         = OutlineSoft,
    scrim                  = Black,
    surfaceDim             = SurfaceDimDark,
    surfaceBright          = SurfaceBrightDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow    = SurfaceContainerLowDark,
    surfaceContainer       = SurfaceContainerDark,
    surfaceContainerHigh   = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark
)

private val LightColorScheme = lightColorScheme(
    primary                = PorchAmberOnLight,
    onPrimary              = White,
    primaryContainer       = PrimaryContainerLight,
    onPrimaryContainer     = OnPrimaryContainerLight,
    inversePrimary         = PorchAmber,
    secondary              = WarningTextLight,
    onSecondary            = White,
    secondaryContainer     = SecondaryContainerLight,
    onSecondaryContainer   = OnSecondaryContainerLight,
    tertiary               = SuccessTextLight,
    onTertiary             = White,
    tertiaryContainer      = TertiaryContainerLight,
    onTertiaryContainer    = OnTertiaryContainerLight,
    error                  = DangerTextLight,
    onError                = White,
    errorContainer         = ErrorContainerLight,
    onErrorContainer       = OnErrorContainerLight,
    background             = SurfaceLight0,
    onBackground           = TextPrimaryLight,
    surface                = SurfaceLight1,
    onSurface              = TextPrimaryLight,
    surfaceVariant         = SurfaceLight2,
    onSurfaceVariant       = TextSecondaryLight,
    surfaceTint            = PorchAmberOnLight,
    inverseSurface         = InverseSurfaceLight,
    inverseOnSurface       = InverseOnSurfaceLight,
    outline                = OutlineMutedLight,
    outlineVariant         = OutlineSoftLight,
    scrim                  = Black,
    surfaceDim             = SurfaceDimLight,
    surfaceBright          = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow    = SurfaceContainerLowLight,
    surfaceContainer       = SurfaceContainerLight,
    surfaceContainerHigh   = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight
)

@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
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

    CompositionLocalProvider(
        LocalStatusPalette provides if (darkTheme) DarkStatusPalette else LightStatusPalette
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography  = AppTypography,
            shapes      = AppShapes,
            content     = content
        )
    }
}

// Compatibility shim — Session 2 will wire MainActivity and CrashAlertActivity to AppTheme directly
@Composable
fun FamilySafetyTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) = AppTheme(darkTheme = darkTheme, content = content)
