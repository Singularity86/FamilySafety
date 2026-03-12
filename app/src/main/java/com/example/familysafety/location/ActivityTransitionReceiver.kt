package com.example.familysafety.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

class ActivityTransitionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ActivityRecognitionEntryPoint {
        fun activityRecognitionManager(): ActivityRecognitionManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: run {
            Timber.w("ActivityTransitionResult.extractResult returned null")
            return
        }
        val manager = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ActivityRecognitionEntryPoint::class.java
        ).activityRecognitionManager()
        manager.onTransitionResult(result)
    }
}
