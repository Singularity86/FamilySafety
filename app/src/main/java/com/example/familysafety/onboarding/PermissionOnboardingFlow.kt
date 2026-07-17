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
                title = PermissionCopy.Location.title,
                rationale = PermissionCopy.Location.rationale,
                coaching = PermissionCopy.Location.coachingText(context),
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
                title = PermissionCopy.BackgroundLocation.title,
                rationale = PermissionCopy.BackgroundLocation.rationale,
                coaching = PermissionCopy.BackgroundLocation.coachingText(context),
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
                title = PermissionCopy.Notifications.title,
                rationale = PermissionCopy.Notifications.rationale,
                coaching = PermissionCopy.Notifications.coachingText(context),
                onRequestPermission = {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onDismiss = { step = nextStep(context, STEP_NEARBY_WIFI) }
            )

            STEP_NEARBY_WIFI -> PermissionRationaleCard(
                permission = Manifest.permission.NEARBY_WIFI_DEVICES,
                title = PermissionCopy.NearbyWifi.title,
                rationale = PermissionCopy.NearbyWifi.rationale,
                coaching = PermissionCopy.NearbyWifi.coachingText(context),
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
