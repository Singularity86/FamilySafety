package com.example.familysafety.chat

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for chat dependencies.
 * ChatRepository is @Singleton with @Inject constructor,
 * so Hilt will provide it automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
    // ChatRepository and ChatViewModel are provided via @Inject constructors.
    // This module can be extended with additional chat-related dependencies.
}
