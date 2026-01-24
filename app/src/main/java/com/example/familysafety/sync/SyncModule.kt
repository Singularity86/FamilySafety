package com.example.familysafety.sync

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.LazysodiumCryptoProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideGroupSyncManager(
        e2eeManager: E2EEManager,
        cryptoProvider: LazysodiumCryptoProvider
    ): GroupSyncManager {
        return GroupSyncManager(e2eeManager, cryptoProvider)
    }
}
