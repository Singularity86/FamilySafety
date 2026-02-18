package com.example.familysafety

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration
import timber.log.Timber

/**
 * Application class for FamilySafety
 */
@HiltAndroidApp
class FamilySafetyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        Timber.plant(Timber.DebugTree())

        // Configure osmdroid (OpenStreetMap tile library).
        // userAgentValue is required by OSM tile servers to identify the app.
        Configuration.getInstance().apply {
            load(this@FamilySafetyApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
    }
}