package com.example.familysafety.onboarding

import android.content.Context
import android.os.Build

/**
 * Single source of truth for permission-onboarding copy (title/rationale/coaching),
 * shared by PermissionOnboardingFlow.kt (first-run flow) and SettingsScreen.kt
 * (re-grant rows), so the two surfaces don't drift out of sync with each other.
 */
sealed class PermissionCopy(
    val title: String,
    val rationale: String
) {
    /** SDK/OS-aware coaching text shown under the rationale. */
    abstract fun coachingText(context: Context): String

    object Location : PermissionCopy(
        title = "Location Access",
        rationale = "Jibaro Family Safety shares your location with your family group in real time. " +
            "Your location is end-to-end encrypted — only your family can ever see it."
    ) {
        override fun coachingText(context: Context) =
            "When prompted, choose 'While using the app' — " +
                "this lets your family see your location whenever Jibaro Family Safety is open."
    }

    object BackgroundLocation : PermissionCopy(
        title = "Always-On Location",
        // Wording deliberately follows Google Play's prominent-disclosure
        // formula for background location; changing it can fail app review.
        rationale = "Jibaro Family Safety collects location data to enable real-time " +
            "location sharing with your family group, even when the app is " +
            "closed or not in use. Your location is end-to-end encrypted, is " +
            "shared only with your family members, and is never sold or given " +
            "to anyone else."
    ) {
        override fun coachingText(context: Context): String {
            // Android 11+ (API 30+) removed the direct "Allow all the time" dialog option —
            // the OS forces a trip to Settings instead. Quote its own label rather than
            // hardcoding "Allow all the time", since OEMs/locales can reword it.
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val label = context.packageManager.backgroundPermissionOptionLabel.toString()
                "You'll be taken to this app's Settings page. Tap Permissions → Location, " +
                    "then choose \"$label\" — this keeps your location visible when your " +
                    "phone is in your pocket."
            } else {
                "On the next screen, choose 'Allow all the time' — " +
                    "this keeps your location visible when your phone is in your pocket."
            }
        }
    }

    object Notifications : PermissionCopy(
        title = "Safety Alerts",
        rationale = "Notifications are how safety alerts — geofence arrivals, crash " +
            "detection, and new messages — reach you instantly."
    ) {
        override fun coachingText(context: Context) =
            "Choose 'Allow' — without this, safety alerts from your family can't reach you."
    }

    object NearbyWifi : PermissionCopy(
        title = "Local WiFi Mode",
        rationale = "When you're on the same WiFi as your family, the app communicates " +
            "directly — no internet required. This needs access to see nearby devices."
    ) {
        override fun coachingText(context: Context) =
            "Choose 'Allow' — this enables faster, internet-free location sharing " +
                "on your home or work network."
    }
}
