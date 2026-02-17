package com.example.familysafety.sync

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    // GroupSyncManager is provided via its @Inject constructor — do not duplicate here.
}
