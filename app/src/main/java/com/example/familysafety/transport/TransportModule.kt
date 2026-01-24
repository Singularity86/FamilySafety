package com.example.familysafety.transport

import android.content.Context
import com.example.familysafety.core.NetworkMonitor
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.location.LocationRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideMqttTransport(
        @ApplicationContext context: Context,
        locationRepository: LocationRepository,
        e2eeManager: E2EEManager,
        networkMonitor: NetworkMonitor
    ): MqttTransport {
        return MqttTransport(context, locationRepository, e2eeManager, networkMonitor)
    }
}
