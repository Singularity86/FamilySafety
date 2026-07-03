package com.example.familysafety.crypto

import android.content.Context
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.group.LocalKeyStore
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
    ): LocalKeyStore {
        return AndroidKeyStoreLocalKeyStore(context)
    }

    @Provides
    @Singleton
    fun provideLazysodiumCryptoProvider(
        keyStore: LocalKeyStore
    ): LazysodiumCryptoProvider {
        return LazysodiumCryptoProvider(keyStore)
    }

    // E2EEManager is provided via its @Inject constructor — do not duplicate here.
}
