package com.example.familysafety

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for FamilySafety
 */
@HiltAndroidApp
class FamilySafetyApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize any singletons or libraries here
        // For example: Timber for logging
        // if (BuildConfig.DEBUG) {
        //     Timber.plant(Timber.DebugTree())
        // }
    }
}