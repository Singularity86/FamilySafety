package com.example.familysafety.geofence

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object GeofenceModule {
    // GeofenceRepository, GeofenceMonitor, and GeofenceNotificationHelper
    // are provided via their @Inject constructors — no explicit bindings needed.
}
