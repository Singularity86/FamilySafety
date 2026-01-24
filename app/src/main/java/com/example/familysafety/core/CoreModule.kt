package com.example.familysafety.core

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: Context
    ): NetworkMonitor {
        return NetworkMonitor(context)
    }
}
