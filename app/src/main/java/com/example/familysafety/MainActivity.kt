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
                                // Restart the activity so Hilt rebuilds all singletons
                                // (LocalMemberId, GroupStateManager, etc.) with the
                                // now-initialized keys. Without a restart they would
                                // retain the empty-string placeholder set at first launch.
                                val intent = intent
                                finish()
                                startActivity(intent)
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
     * Check if user has completed onboarding by checking if keys exist
     */
    private fun checkIfOnboarded(): Boolean {
        val prefs = getSharedPreferences("familysafety_prefs", MODE_PRIVATE)
        return prefs.getBoolean("onboarding_complete", false) ||
                // Check if keystore has keys
                try {
                    val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                    keyStore.load(null)
                    keyStore.containsAlias("familysafety_ed25519_private")
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
     * Start the foreground location service
     */
    private fun startLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
