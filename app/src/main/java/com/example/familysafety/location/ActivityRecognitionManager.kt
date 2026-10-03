package com.example.familysafety.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

@Singleton
class ActivityRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Default to moving so we start with the most frequent update interval
    private val _isMoving = MutableStateFlow(true)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    private val _isInVehicle = MutableStateFlow(false)
    val isInVehicle: StateFlow<Boolean> = _isInVehicle.asStateFlow()

    private var pendingIntent: PendingIntent? = null

    /**
     * Whether the phone lets us read motion ("Physical activity" on Android 10+; a Play
     * services permission before that). Without it the location service simply runs on GPS
     * speed alone (see LocationService.adjustIntervalFromGps); asking anyway made the call
     * fail, and on some Play services versions throw, from inside the service's start-up.
     */
    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACTIVITY_RECOGNITION
        } else {
            "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun startMonitoring() {
        if (pendingIntent != null) return
        if (!hasPermission()) {
            Timber.i("Activity recognition not permitted — using GPS speed for movement")
            return
        }

        val intent = Intent(context, ActivityTransitionReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        pendingIntent = pi

        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )

        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pi)
                .addOnSuccessListener { Timber.i("Activity recognition monitoring started") }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to start activity recognition")
                    // Otherwise the guard above makes every later startMonitoring() a no-op.
                    if (pendingIntent === pi) pendingIntent = null
                }
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
            Timber.w(e, "Activity recognition permission missing — using GPS speed for movement")
            pendingIntent = null
        }
    }

    fun stopMonitoring() {
        val pi = pendingIntent ?: return
        try {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(pi)
                .addOnSuccessListener {
                    Timber.i("Activity recognition monitoring stopped")
                    pendingIntent = null
                }
                .addOnFailureListener { e -> Timber.e(e, "Failed to stop activity recognition") }
        } catch (e: SecurityException) {
            // Permission revoked while monitoring; there is nothing registered we can reach.
            Timber.w(e, "Could not stop activity recognition — permission missing")
            pendingIntent = null
        }
    }

    fun onTransitionResult(result: ActivityTransitionResult) {
        for (event in result.transitionEvents) {
            when {
                event.activityType == DetectedActivity.STILL &&
                event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                    Timber.d("Activity: device is now STILL")
                    _isMoving.value = false
                }
                event.activityType == DetectedActivity.STILL &&
                event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                    Timber.d("Activity: device is now MOVING")
                    _isMoving.value = true
                }
                event.activityType == DetectedActivity.IN_VEHICLE &&
                event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                    Timber.d("Activity: device is now IN_VEHICLE")
                    _isInVehicle.value = true
                }
                event.activityType == DetectedActivity.IN_VEHICLE &&
                event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                    Timber.d("Activity: device left IN_VEHICLE")
                    _isInVehicle.value = false
                }
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 1001
    }
}
