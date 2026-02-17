package com.example.familysafety.core

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    // NetworkMonitor is provided via its @Inject constructor — do not duplicate here.
}
