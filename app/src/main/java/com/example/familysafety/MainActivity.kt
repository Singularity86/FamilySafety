package com.example.familysafety

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineLocationGranted || coarseLocationGranted) {
            startLocationService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FamilySafetyTheme {
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
                        }

                        MainScreen()
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

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            appInitializer.cleanup()
        }
    }

    /**
     * Check if user has completed onboarding.
     *
     * Primary check: the "onboarding_complete" flag written by OnboardingViewModel
     * after createFamily()/joinFamily() succeeds.
     *
     * Fallback: directly ask AndroidKeyStoreLocalKeyStore whether keys have been
     * initialized, which handles the edge case where the pref was written but the
     * app data was partially cleared.
     */
    private fun checkIfOnboarded(): Boolean {
        val prefs = getSharedPreferences("familysafety_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_complete", false)) return true
        return try {
            AndroidKeyStoreLocalKeyStore(applicationContext).isInitialized()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check and request location permissions
     */
    private fun checkAndRequestLocationPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Add background location for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        val needsPermission = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermission) {
            locationPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            startLocationService()
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
