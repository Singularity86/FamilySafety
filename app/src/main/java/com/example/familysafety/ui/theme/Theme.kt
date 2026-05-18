package com.example.familysafety.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
private val DarkColorScheme = darkColorScheme(
    primary          = TealPrimary,
    onPrimary        = Black,
    secondary        = AmberWarning,
    onSecondary      = Black,
    tertiary         = RedDanger,
    onTertiary       = TextPrimary,
    error            = RedDanger,
    onError          = TextPrimary,
    background       = Surface0,
    onBackground     = TextPrimary,
    surface          = Surface1,
    onSurface        = TextPrimary,
    surfaceVariant   = Surface2,
    onSurfaceVariant = TextSecondary,
    outline          = OutlineMuted,
    outlineVariant   = OutlineSoft
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
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
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AppTypography,
        shapes      = AppShapes,
        content     = content
    )
}

// Compatibility shim — Session 2 will wire MainActivity and CrashAlertActivity to AppTheme directly
@Composable
fun FamilySafetyTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) = AppTheme(content = content)
