package com.example.familysafety

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.location.LocationService
import com.example.familysafety.onboarding.OnboardingNavigation
import com.example.familysafety.onboarding.PermissionOnboardingFlow
import com.example.familysafety.onboarding.TutorialScreen
import com.example.familysafety.onboarding.isPermissionsFlowShown
import com.example.familysafety.main.MainScreen
import com.example.familysafety.main.TipWindow
import com.example.familysafety.main.disableTips
import com.example.familysafety.main.incrementSessionCount
import com.example.familysafety.main.shouldShowTip
import com.example.familysafety.ui.theme.AppTheme
import com.example.familysafety.ui.theme.ThemeMode
import com.example.familysafety.ui.theme.ThemePreference
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main entry point for the application
 * Checks if keys are initialized and shows onboarding or main screen accordingly
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appInitializer: AppInitializer

    @Inject
    lateinit var localMemberId: LocalMemberId

    // Tracks the tab the app should navigate to (set from notification tap).
    private val navigateTo = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let Compose handle insets (keyboard, status bar) instead of the OS resizing the window.
        // Without this, imePadding() fights the window resize and causes a blank-screen flash.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        navigateTo.value = intent?.getStringExtra("navigate_to")

        setContent {
            val systemDark = isSystemInDarkTheme()
            var themeMode by remember { mutableStateOf(ThemePreference.get(this@MainActivity)) }
            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }

            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isOnboarded by remember { mutableStateOf(checkIfOnboarded()) }

                    if (isOnboarded) {
                        // Initialize app components when showing main screen
                        LaunchedEffect(Unit) {
                            incrementSessionCount(this@MainActivity)
                            appInitializer.initialize()
                        }

                        // Show the sequential permission flow on first launch after onboarding.
                        var showPermissionFlow by remember {
                            mutableStateOf(!isPermissionsFlowShown(this@MainActivity))
                        }

                        // Start location service once the permission flow is done (or was already done).
                        LaunchedEffect(showPermissionFlow) {
                            if (!showPermissionFlow) {
                                val fineGranted = ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                                val coarseGranted = ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                                if (fineGranted || coarseGranted) startLocationService()
                            }
                        }

                        var showTip by remember { mutableStateOf(shouldShowTip(this@MainActivity)) }
                        var showTutorialOverlay by remember { mutableStateOf(false) }
                        var navigateToSettings by remember { mutableStateOf(false) }

                        MainScreen(
                            navigateTo = if (navigateToSettings) {
                                navigateToSettings = false
                                "settings"
                            } else navigateTo.value,
                            onThemeChanged = { mode ->
                                ThemePreference.set(this@MainActivity, mode)
                                themeMode = mode
                            },
                            onReplayTutorial = { showTutorialOverlay = true }
                        )

                        // Sequential permission explanation flow — shown once, right after first launch.
                        if (showPermissionFlow) {
                            PermissionOnboardingFlow(
                                onComplete = { showPermissionFlow = false }
                            )
                        }

                        // Tips only appear when no other overlay is active.
                        if (showTip && !showPermissionFlow) {
                            TipWindow(
                                onDismiss = { showTip = false },
                                onDisableTips = {
                                    disableTips(this@MainActivity)
                                    showTip = false
                                },
                                onOpenSettings = { navigateToSettings = true }
                            )
                        }

                        if (showTutorialOverlay) {
                            TutorialScreen(
                                onFinish = { showTutorialOverlay = false }
                            )
                        }
                    } else {
                        OnboardingNavigation(
                            onOnboardingComplete = {
                                // Force a full process restart so the Hilt SingletonComponent
                                // is rebuilt from scratch with the now-initialized keys.
                                // finish()+startActivity() is not enough — it reuses the
                                // same process and the same stale singletons (LocalMemberId="",
                                // GroupStateManager with empty member ID).
                                val restartIntent = packageManager
                                    .getLaunchIntentForPackage(packageName)!!
                                    .apply {
                                        addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        )
                                    }
                                startActivity(restartIntent)
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigateTo.value = intent.getStringExtra("navigate_to")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            appInitializer.cleanup()
        }
    }

    /**
     * Check if the user has genuinely completed onboarding.
     *
     * Requires BOTH conditions to be true:
     *  1. The "onboarding_complete" flag is set.
     *  2. The cryptographic keys are actually present and readable.
     *
     * If only the flag is set but the keys are gone (e.g. restored from backup
     * after a reinstall while the Keystore entries were not), we clear the stale
     * flag and return false so the onboarding flow runs again.
     */
    private fun checkIfOnboarded(): Boolean {
        val prefs = getSharedPreferences("familysafety_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("onboarding_complete", false)) return false
        return try {
            val keysReady = AndroidKeyStoreLocalKeyStore(applicationContext).isInitialized()
            if (!keysReady) {
                // Flag set but keys missing — stale backup state.
                prefs.edit().remove("onboarding_complete").commit()
            }
            keysReady
        } catch (e: Exception) {
            // Keys unreadable — clear the flag so onboarding runs fresh.
            prefs.edit().remove("onboarding_complete").commit()
            false
        }
    }

    private fun startLocationService() {
        LocationService.startTracking(this, localMemberId.value)
    }
}
