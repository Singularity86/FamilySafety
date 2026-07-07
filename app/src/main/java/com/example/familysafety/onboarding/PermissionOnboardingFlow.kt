package com.example.familysafety.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.familysafety.ui.components.PermissionRationaleCard

private const val STEP_LOCATION = 0
private const val STEP_BG_LOCATION = 1
private const val STEP_NOTIFICATIONS = 2
private const val STEP_NEARBY_WIFI = 3
private const val STEP_BATTERY = 4
private const val STEP_DONE = 5

@SuppressLint("InlinedApi")
private fun shouldSkipStep(context: Context, step: Int): Boolean = when (step) {
    STEP_LOCATION ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    STEP_BG_LOCATION ->
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        // Background location requires foreground location — skip if foreground was denied.
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    STEP_NOTIFICATIONS ->
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    STEP_NEARBY_WIFI ->
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

    STEP_BATTERY ->
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    else -> true
}

private fun nextStep(context: Context, from: Int): Int {
    var s = from
    while (s < STEP_DONE && shouldSkipStep(context, s)) s++
    return s
}

fun isPermissionsFlowShown(context: Context): Boolean =
    context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
        .getBoolean("permissions_flow_shown", false) &&
        hasAlwaysOnLocationPrerequisites(context)

private fun hasAlwaysOnLocationPrerequisites(context: Context): Boolean {
    val hasForeground =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    val batteryOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    return hasForeground && hasBackground && batteryOk
}

private fun markPermissionsFlowShown(context: Context) {
    context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
        .edit().putBoolean("permissions_flow_shown", true).apply()
}

@SuppressLint("InlinedApi")
@Composable
fun PermissionOnboardingFlow(onComplete: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(nextStep(context, STEP_LOCATION)) }

    // All launchers must be declared unconditionally at the top of the composable.

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        // Skip background location if foreground was denied — OS won't grant it anyway.
        step = if (granted) nextStep(context, STEP_BG_LOCATION)
               else nextStep(context, STEP_NOTIFICATIONS)
    }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { step = nextStep(context, STEP_BG_LOCATION) }

    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { step = nextStep(context, STEP_BG_LOCATION) }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { step = nextStep(context, STEP_NEARBY_WIFI) }

    val nearbyWifiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { step = nextStep(context, STEP_BATTERY) }

    if (step == STEP_DONE) {
        LaunchedEffect(Unit) {
            markPermissionsFlowShown(context)
            onComplete()
        }
        return
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        when (step) {
            STEP_LOCATION -> PermissionRationaleCard(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                title = "Location Access",
                rationale = "FamilySafety shares your location with your family group in real time. " +
                    "Your location is end-to-end encrypted — only your family can ever see it.",
                coaching = "When prompted, choose 'While using the app' — " +
                    "this lets your family see your location whenever FamilySafety is open.",
                onRequestPermission = {
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                onDismiss = { step = nextStep(context, STEP_NOTIFICATIONS) }
            )

            STEP_BG_LOCATION -> PermissionRationaleCard(
                permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                title = "Always-On Location",
                // Wording deliberately follows Google Play's prominent-disclosure
                // formula for background location; changing it can fail app review.
                rationale = "FamilySafety collects location data to enable real-time " +
                    "location sharing with your family group, even when the app is " +
                    "closed or not in use. Your location is end-to-end encrypted, is " +
                    "shared only with your family members, and is never sold or given " +
                    "to anyone else.",
                coaching = "On the next screen, choose 'Allow all the time' — " +
                    "this keeps your location visible when your phone is in your pocket.",
                onRequestPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        appSettingsLauncher.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    } else {
                        bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                },
                onDismiss = { step = nextStep(context, STEP_NOTIFICATIONS) }
            )

            STEP_NOTIFICATIONS -> PermissionRationaleCard(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                title = "Safety Alerts",
                rationale = "Notifications are how safety alerts — geofence arrivals, crash " +
                    "detection, and new messages — reach you instantly.",
                coaching = "Choose 'Allow' — without this, safety alerts from your family can't reach you.",
                onRequestPermission = {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onDismiss = { step = nextStep(context, STEP_NEARBY_WIFI) }
            )

            STEP_NEARBY_WIFI -> PermissionRationaleCard(
                permission = Manifest.permission.NEARBY_WIFI_DEVICES,
                title = "Local WiFi Mode",
                rationale = "When you're on the same WiFi as your family, the app communicates " +
                    "directly — no internet required. This needs access to see nearby devices.",
                coaching = "Choose 'Allow' — this enables faster, internet-free location sharing " +
                    "on your home or work network.",
                onRequestPermission = {
                    nearbyWifiLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                },
                onDismiss = { step = nextStep(context, STEP_BATTERY) }
            )

            STEP_BATTERY -> BatteryOptimizationScreen(
                onAllowed = { step = STEP_DONE },
                onSkip = { step = STEP_DONE }
            )
        }
    }
}
