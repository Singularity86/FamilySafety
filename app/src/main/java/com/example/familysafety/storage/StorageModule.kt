package com.example.familysafety.storage

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for database and storage dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): FamilySafetyDatabase {
        return FamilySafetyDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideLocationHistoryDao(
        database: FamilySafetyDatabase
    ): LocationHistoryDao {
        return database.locationHistoryDao()
    }

    @Provides
    @Singleton
    fun provideChatMessageDao(
        database: FamilySafetyDatabase
    ): ChatMessageDao {
        return database.chatMessageDao()
    }
}
