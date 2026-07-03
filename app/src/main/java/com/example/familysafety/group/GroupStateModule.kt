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
        return try {
            val publicKey = cryptoProvider.getLocalEd25519PublicKey()
            val memberId = cryptoProvider.deriveMemberId(publicKey)
            LocalMemberId(memberId)
        } catch (e: Exception) {
            // Keys not yet initialized (or unreadable) — user hasn't completed onboarding.
            // The app will show onboarding; MainActivity restarts after completion
            // so Hilt rebuilds this singleton with the real member ID.
            LocalMemberId("")
        }
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

