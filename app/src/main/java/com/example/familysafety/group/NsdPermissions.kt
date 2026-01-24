package com.example.familysafety.group

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Permission requirements for NSD (Network Service Discovery) on various Android versions.
 *
 * Android Version | Required Permissions
 * ----------------|----------------------------------------------------
 * < 12 (API 31)   | ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE
 * 12 (API 31)     | + ACCESS_FINE_LOCATION (for NSD)
 * 13+ (API 33+)   | + NEARBY_WIFI_DEVICES (replaces location for NSD)
 *
 * Reference: https://developer.android.com/about/versions/13/features/nearby-wifi-devices-permission
 */
object NsdPermissions {

    // Base permissions required for all Android versions
    private val BASE_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
    )

    // Location permission for Android 12 (required for NSD before NEARBY_WIFI_DEVICES)
    private val LOCATION_PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

    // New permission for Android 13+
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private const val NEARBY_WIFI_PERMISSION = Manifest.permission.NEARBY_WIFI_DEVICES

    /**
     * Get all permissions required for NSD on the current device.
     */
    fun getRequiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+: NEARBY_WIFI_DEVICES instead of location
                BASE_PERMISSIONS + NEARBY_WIFI_PERMISSION
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                // Android 12: Requires location for NSD
                BASE_PERMISSIONS + LOCATION_PERMISSION
            }
            else -> {
                // Android < 12: Base permissions only
                BASE_PERMISSIONS
            }
        }
    }

    /**
     * Check if all required NSD permissions are granted.
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Get list of permissions that are not yet granted.
     */
    fun getMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if rationale should be shown for any missing permissions.
     */
    fun shouldShowRationale(activity: ComponentActivity): Boolean {
        return getMissingPermissions(activity).any { permission ->
            activity.shouldShowRequestPermissionRationale(permission)
        }
    }
}

/**
 * Result of a permission request.
 */
sealed class PermissionResult {
    /** All requested permissions were granted. */
    object Granted : PermissionResult()

    /** Some or all permissions were denied. */
    data class Denied(val deniedPermissions: List<String>) : PermissionResult()

    /** User denied with "Don't ask again" - must go to settings. */
    data class PermanentlyDenied(val permissions: List<String>) : PermissionResult()
}

/**
 * Helper class for managing NSD permission requests.
 *
 * Usage in Activity/Fragment:
 * ```
 * class MyActivity : ComponentActivity() {
 *     private val permissionHelper = NsdPermissionHelper()
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         permissionHelper.register(this)
 *
 *         lifecycleScope.launch {
 *             permissionHelper.permissionState.collect { result ->
 *                 when (result) {
 *                     is PermissionResult.Granted -> startNsdDiscovery()
 *                     is PermissionResult.Denied -> showRationale()
 *                     is PermissionResult.PermanentlyDenied -> showSettingsPrompt()
 *                 }
 *             }
 *         }
 *     }
 *
 *     private fun requestPermissions() {
 *         permissionHelper.requestPermissions()
 *     }
 * }
 * ```
 */
class NsdPermissionHelper {

    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var activity: ComponentActivity? = null

    private val _permissionState = MutableStateFlow<PermissionResult?>(null)
    val permissionState: StateFlow<PermissionResult?> = _permissionState.asStateFlow()

    /**
     * Register the permission launcher with the activity.
     * Must be called in onCreate() before onStart().
     */
    fun register(activity: ComponentActivity) {
        this.activity = activity

        launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            handlePermissionResult(permissions)
        }
    }

    /**
     * Request all required NSD permissions.
     */
    fun requestPermissions() {
        val act = activity ?: throw IllegalStateException("Not registered with activity")
        val launcher = launcher ?: throw IllegalStateException("Launcher not initialized")

        val missingPermissions = NsdPermissions.getMissingPermissions(act)

        if (missingPermissions.isEmpty()) {
            _permissionState.value = PermissionResult.Granted
        } else {
            launcher.launch(missingPermissions.toTypedArray())
        }
    }

    /**
     * Check current permission status without requesting.
     */
    fun checkPermissions(): PermissionResult {
        val act = activity ?: throw IllegalStateException("Not registered with activity")

        val missingPermissions = NsdPermissions.getMissingPermissions(act)

        return if (missingPermissions.isEmpty()) {
            PermissionResult.Granted
        } else {
            PermissionResult.Denied(missingPermissions)
        }
    }

    private fun handlePermissionResult(permissions: Map<String, Boolean>) {
        val act = activity ?: return

        val deniedPermissions = permissions.filterValues { !it }.keys.toList()

        if (deniedPermissions.isEmpty()) {
            _permissionState.value = PermissionResult.Granted
            return
        }

        // Check if any were permanently denied (user selected "Don't ask again")
        val permanentlyDenied = deniedPermissions.filter { permission ->
            !act.shouldShowRequestPermissionRationale(permission)
        }

        if (permanentlyDenied.isNotEmpty()) {
            _permissionState.value = PermissionResult.PermanentlyDenied(permanentlyDenied)
        } else {
            _permissionState.value = PermissionResult.Denied(deniedPermissions)
        }
    }

    /**
     * Unregister when activity is destroyed.
     */
    fun unregister() {
        launcher = null
        activity = null
    }
}

/**
 * Permission rationale messages for user-facing UI.
 */
object NsdPermissionRationale {

    fun getRationaleMessage(): String {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                """
                FamilySafe needs "Nearby devices" permission to discover family members 
                on your local WiFi network. This enables faster, direct connections 
                without using the internet.
                
                This permission is only used for local device discovery and does not 
                track your location or share data with third parties.
                """.trimIndent()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                """
                FamilySafe needs location permission to discover family members on 
                your local WiFi network. On Android 12, WiFi scanning requires 
                location access.
                
                This permission is only used for local device discovery. Your location 
                is not tracked or shared.
                """.trimIndent()
            }
            else -> {
                """
                FamilySafe needs WiFi permissions to discover family members on your 
                local network for faster, direct connections.
                """.trimIndent()
            }
        }
    }

    fun getSettingsPromptMessage(): String {
        return """
        FamilySafe cannot discover devices on your local network without the 
        required permissions.
        
        Please go to Settings → Apps → FamilySafe → Permissions and enable 
        the required permissions.
        """.trimIndent()
    }
}
