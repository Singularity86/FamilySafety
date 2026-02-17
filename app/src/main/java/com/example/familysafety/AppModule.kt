package com.example.familysafety

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * App-level Hilt module for application-wide dependencies.
 * Note: @ApplicationContext Context is provided by Hilt automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // Hilt provides @ApplicationContext Context out of the box — no explicit binding needed.
}