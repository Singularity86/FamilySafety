package com.example.familysafety.crypto

import android.content.Context
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.group.Slip10CryptoProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideLocalKeyStore(
        @ApplicationContext context: Context
    ): Slip10CryptoProvider.LocalKeyStore {
        return AndroidKeyStoreLocalKeyStore(context)
    }

    @Provides
    @Singleton
    fun provideLazysodiumCryptoProvider(
        keyStore: Slip10CryptoProvider.LocalKeyStore
    ): LazysodiumCryptoProvider {
        return LazysodiumCryptoProvider(keyStore)
    }

    @Provides
    @Singleton
    fun provideE2EEManager(
        cryptoProvider: LazysodiumCryptoProvider
    ): E2EEManager {
        return E2EEManager(cryptoProvider)
    }
}
