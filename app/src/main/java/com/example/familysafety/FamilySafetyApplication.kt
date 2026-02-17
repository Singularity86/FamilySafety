package com.example.familysafety

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for FamilySafety
 */
@HiltAndroidApp
class FamilySafetyApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber for logging
        // Always plant in debug builds
        Timber.plant(Timber.DebugTree())
    }
}