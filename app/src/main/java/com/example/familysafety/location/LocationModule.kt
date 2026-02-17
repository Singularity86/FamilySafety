package com.example.familysafety.location

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object LocationModule {
    // LocationRepository is provided via its @Inject constructor — do not duplicate here.
}
