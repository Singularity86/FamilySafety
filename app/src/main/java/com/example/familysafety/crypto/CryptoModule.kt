package com.example.familysafety.crypto

import com.example.familysafety.group.LazysodiumCryptoProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideE2EEManager(
        cryptoProvider: LazysodiumCryptoProvider
    ): E2EEManager {
        return E2EEManager(cryptoProvider)
    }
}
