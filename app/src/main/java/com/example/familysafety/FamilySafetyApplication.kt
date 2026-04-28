package com.example.familysafety

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration
import timber.log.Timber
import java.io.File

/**
 * Application class for FamilySafety
 */
@HiltAndroidApp
class FamilySafetyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging. Only plant the verbose DebugTree in
        // debug builds — in release we'd otherwise leak location coords, member
        // IDs, and other PII to logcat.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Configure osmdroid (OpenStreetMap tile library).
        // userAgentValue is required by OSM tile servers to identify the app.
        Configuration.getInstance().apply {
            load(this@FamilySafetyApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            // More download threads = tiles appear much faster when zooming/panning.
            // Default is 2; 4 is safe and noticeably faster.
            tileDownloadThreads = 4
            tileDownloadMaxQueueSize = 40
            // Store tile cache on external storage if available — more space, survives low-storage warnings.
            val extCache = getExternalFilesDir(null)
            if (extCache != null) {
                osmdroidTileCache = File(extCache, "osmdroid_tiles")
            }
            // Cap the on-disk tile cache to 150 MB; trim to 100 MB when pruning.
            tileFileSystemCacheMaxBytes = 150L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 100L * 1024 * 1024
        }
    }
}