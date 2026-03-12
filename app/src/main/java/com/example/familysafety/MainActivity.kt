package com.example.familysafety

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.familysafety.main.MainScreen
import com.example.familysafety.ui.theme.FamilySafetyTheme
import com.example.familysafety.ui.theme.ThemeMode
import com.example.familysafety.ui.theme.ThemePreference
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

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineLocationGranted || coarseLocationGranted) {
            startLocationService()
        }
    }

    // Nearby Wi-Fi Devices permission — required for mDNS (NSD) on Android 13+.
    // Silently ignored if denied; avatar sync via NSD simply won't work on LAN.
    private val nearbyWifiLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: NSD start already wrapped in try-catch for SecurityException */ }

    // POST_NOTIFICATIONS is a runtime permission on Android 13+.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, notifications simply won't appear if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigateTo.value = intent?.getStringExtra("navigate_to")

        setContent {
            val systemDark = isSystemInDarkTheme()
            var themeMode by remember { mutableStateOf(ThemePreference.get(this@MainActivity)) }
            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }

            FamilySafetyTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isOnboarded by remember { mutableStateOf(checkIfOnboarded()) }

                    if (isOnboarded) {
                        // Initialize app components when showing main screen
                        LaunchedEffect(Unit) {
                            appInitializer.initialize()
                            checkAndRequestLocationPermissions()
                            requestNearbyWifiPermissionIfNeeded()
                            requestNotificationPermissionIfNeeded()
                            requestBatteryOptimizationExemptionIfNeeded()
                        }

                        MainScreen(
                            navigateTo = navigateTo.value,
                            onThemeChanged = { mode ->
                                ThemePreference.set(this@MainActivity, mode)
                                themeMode = mode
                            }
                        )
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

    /**
     * Check and request foreground location permissions.
     * Background location is NOT requested here — Android 11+ requires it to be
     * requested separately after foreground is granted, and we don't need it for
     * basic family location sharing.
     */
    private fun checkAndRequestLocationPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val needsPermission = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermission) {
            locationPermissionLauncher.launch(permissions)
        } else {
            startLocationService()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(perm)
            }
        }
    }

    /**
     * Request NEARBY_WIFI_DEVICES on Android 13+ for mDNS-based local avatar sync.
     * This is a best-effort request; denial is handled gracefully in AvatarRepository.
     */
    private fun requestNearbyWifiPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = Manifest.permission.NEARBY_WIFI_DEVICES
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                nearbyWifiLauncher.launch(perm)
            }
        }
    }

    /**
     * Ask the OS to exempt this app from battery optimization so the location
     * foreground service isn't throttled or killed when the screen is off.
     * This is particularly important on Samsung/Xiaomi/Oppo devices with
     * aggressive battery management.
     */
    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            // Device doesn't support the direct request dialog; silently ignore.
        }
    }

    /**
     * Start the foreground location service with the local member ID so it
     * can tag each location update correctly.
     */
    private fun startLocationService() {
        LocationService.startTracking(this, localMemberId.value)
    }
}
