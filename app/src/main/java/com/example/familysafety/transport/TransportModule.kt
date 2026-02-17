package com.example.familysafety.transport

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {
    // MqttTransport is provided via its @Inject constructor — do not duplicate here.
}
