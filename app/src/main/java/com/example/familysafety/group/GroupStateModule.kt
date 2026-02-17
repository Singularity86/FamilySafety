package com.example.familysafety.group

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Dependency Injection module for GroupStateManager and related components.
 *
 * Provides singleton instances of:
 * - CryptoProvider (backed by Android Keystore)
 * - GroupStatePersistence (encrypted DataStore)
 * - GroupStateManager (central orchestrator)
 */
@Module
@InstallIn(SingletonComponent::class)
object GroupStateModule {

/**
     * Bind the CryptoProvider interface to the existing LazysodiumCryptoProvider singleton.
     */
    @Provides
    @Singleton
    fun provideCryptoProvider(
        impl: LazysodiumCryptoProvider
    ): CryptoProvider {
        return impl
    }

    /**
     * Provide encrypted persistence for group state.
     */
    @Provides
    @Singleton
    fun provideGroupStatePersistence(
        @ApplicationContext context: Context
    ): GroupStatePersistence {
        return EncryptedGroupStatePersistence.getInstance(context)
    }

    /**
     * Provide the local member ID.
     *
     * This is derived from the Ed25519 public key and should be computed
     * once after key initialization.
     */
    @Provides
    @Singleton
    fun provideLocalMemberId(
        cryptoProvider: CryptoProvider
    ): LocalMemberId {
        val publicKey = cryptoProvider.getLocalEd25519PublicKey()
        val memberId = cryptoProvider.deriveMemberId(publicKey)
        return LocalMemberId(memberId)
    }

    /**
     * Provide the main GroupStateManager.
     */
    @Provides
    @Singleton
    fun provideGroupStateManager(
        localMemberId: LocalMemberId,
        persistence: GroupStatePersistence,
        cryptoProvider: CryptoProvider
    ): GroupStateManager {
        return GroupStateManager(
            localMemberId = localMemberId.value,
            persistence = persistence,
            cryptoProvider = cryptoProvider
        )
    }
}

/**
 * Wrapper for local member ID to enable Hilt injection.
 */
data class LocalMemberId(val value: String)

// =============================================================================
// ALTERNATIVE: Koin Module (if not using Hilt)
// =============================================================================

/*
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val groupStateModule = module {

    single<Slip10CryptoProvider.LocalKeyStore> {
        AndroidKeyStoreLocalKeyStore(androidContext())
    }

    single<CryptoProvider> {
        Slip10CryptoProvider(get())
    }

    single<GroupStatePersistence> {
        EncryptedGroupStatePersistence(androidContext())
    }

    single {
        val cryptoProvider: CryptoProvider = get()
        val publicKey = cryptoProvider.getLocalEd25519PublicKey()
        LocalMemberId(cryptoProvider.deriveMemberId(publicKey))
    }

    single {
        GroupStateManager(
            localMemberId = get<LocalMemberId>().value,
            persistence = get(),
            cryptoProvider = get()
        )
    }
}
*/

// =============================================================================
// Manual Factory (if not using any DI framework)
// =============================================================================

/**
 * Factory for creating GroupStateManager without a DI framework.
 * Useful for testing or simple applications.
 */
object GroupStateManagerFactory {

    @Volatile
    private var instance: GroupStateManager? = null

    /**
     * Get or create the singleton GroupStateManager.
     *
     * Note: Keys must be initialized before calling this.
     * Call CryptoProviderFactory.initializeFromMnemonic() first.
     */
    fun getInstance(context: Context): GroupStateManager {
        return instance ?: synchronized(this) {
            instance ?: createInstance(context).also { instance = it }
        }
    }

    private fun createInstance(context: Context): GroupStateManager {
        val appContext = context.applicationContext

        val keyStore = AndroidKeyStoreLocalKeyStore(appContext)
        check(keyStore.isInitialized()) {
            "Keys not initialized. Call initializeKeys() first."
        }

        val cryptoProvider = LazysodiumCryptoProvider(keyStore)
        val persistence = EncryptedGroupStatePersistence.getInstance(appContext)

        val localMemberId = cryptoProvider.deriveMemberId(
            cryptoProvider.getLocalEd25519PublicKey()
        )

        return GroupStateManager(
            localMemberId = localMemberId,
            persistence = persistence,
            cryptoProvider = cryptoProvider
        )
    }

    /**
     * Initialize keys from mnemonic (must be called before getInstance).
     */
    fun initializeKeys(
        context: Context,
        mnemonic: String,
        passphrase: String = "",
        accountIndex: Int = 0
    ) {
        val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
        val keyStore = AndroidKeyStoreLocalKeyStore(context.applicationContext)
        keyStore.initializeFromSeed(seed, accountIndex)
        seed.fill(0)
    }

    /**
     * Check if keys have been initialized.
     */
    fun isInitialized(context: Context): Boolean {
        val keyStore = AndroidKeyStoreLocalKeyStore(context.applicationContext)
        return keyStore.isInitialized()
    }

    /**
     * Clear the singleton instance (for testing).
     */
    fun clearInstance() {
        synchronized(this) {
            instance = null
        }
    }
}

