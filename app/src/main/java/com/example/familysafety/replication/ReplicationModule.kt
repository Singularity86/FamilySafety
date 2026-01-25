package com.example.familysafety.replication

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for replication dependencies.
 * ReplicationManager is provided via @Inject constructor.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReplicationModule {
    // ReplicationManager is @Singleton with @Inject constructor,
    // so Hilt will provide it automatically.
    // This module can be used to provide additional dependencies if needed.
}
