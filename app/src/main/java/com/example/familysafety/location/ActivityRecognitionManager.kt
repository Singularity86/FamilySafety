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

@Singleton
class ActivityRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Default to moving so we start with the most frequent update interval
    private val _isMoving = MutableStateFlow(true)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    private var pendingIntent: PendingIntent? = null

    fun startMonitoring() {
        if (pendingIntent != null) return

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
                .build()
        )

        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pi)
            .addOnSuccessListener { Timber.i("Activity recognition monitoring started") }
            .addOnFailureListener { e -> Timber.e(e, "Failed to start activity recognition") }
    }

    fun stopMonitoring() {
        val pi = pendingIntent ?: return
        ActivityRecognition.getClient(context)
            .removeActivityTransitionUpdates(pi)
            .addOnSuccessListener {
                Timber.i("Activity recognition monitoring stopped")
                pendingIntent = null
            }
            .addOnFailureListener { e -> Timber.e(e, "Failed to stop activity recognition") }
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
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 1001
    }
}
