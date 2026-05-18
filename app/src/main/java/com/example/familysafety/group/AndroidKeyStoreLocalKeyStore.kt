package com.example.familysafety.group

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.io.File
import java.security.KeyStore

/**
 * Android Keystore-backed implementation of LocalKeyStore.
 *
 * Security model:
 * - Encryption key for stored keys resides in Android Keystore (hardware-backed when available)
 * - Derived Ed25519/X25519 keys are encrypted and stored in EncryptedSharedPreferences
 * - Keys never leave the device in plaintext
 *
 * IMPORTANT: Ed25519 key storage
 * - We store the 32-byte SEED, not the 64-byte secret key
 * - The 64-byte secret key is derived on-demand from the seed
 * - This matches SLIP-10 semantics and Lazysodium expectations
 */
class AndroidKeyStoreLocalKeyStore(
    private val context: Context
) : Slip10CryptoProvider.LocalKeyStore {

    companion object {
        private const val PREFS_NAME = "familysafe_keys"
        private const val KEYSTORE_ALIAS = "familysafe_key_encryption_key"

        // SharedPreferences keys - storing SEEDS not full keys
        private const val KEY_ED25519_SEED = "ed25519_seed"
        private const val KEY_ED25519_PUBLIC = "ed25519_public"
        private const val KEY_X25519_PRIVATE = "x25519_private"
        private const val KEY_X25519_PUBLIC = "x25519_public"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_ACCOUNT_INDEX = "account_index"
        private const val KEY_MNEMONIC = "mnemonic"
    }

    private val sodium = LazySodiumAndroid(SodiumAndroid())

    private val encryptedPrefs: SharedPreferences by lazy {
        createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keyset is corrupted or encrypted with a key that no longer exists
            // (common after reinstall with cloud-restored SharedPreferences, or key rotation).
            // The stored keys are unrecoverable — wipe and start fresh so the app can launch.
            clearCorruptedKeyStorage()
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private fun clearCorruptedKeyStorage() {
        // Clear the SharedPreferences XML file (holds the encrypted Tink keyset)
        try { context.deleteSharedPreferences(PREFS_NAME) } catch (_: Exception) {}
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.dataDir, "shared_prefs/$PREFS_NAME.xml").delete()

        try {
            // Delete possible master-key aliases from AndroidKeyStore so a fresh key is generated.
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            listOf(MasterKey.DEFAULT_MASTER_KEY_ALIAS, KEYSTORE_ALIAS).forEach { alias ->
                if (keyStore.containsAlias(alias)) {
                    keyStore.deleteEntry(alias)
                }
            }
        } catch (_: Exception) {
            // Best-effort cleanup. Recreating EncryptedSharedPreferences below will surface
            // any unrecoverable platform failure.
        }

        // If keys are gone, onboarding must be recalculated on next launch.
        context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove("onboarding_complete")
            .apply()
    }

    // Cached keys (loaded once from encrypted storage)
    private var cachedEd25519Seed: ByteArray? = null
    private var cachedEd25519Public: ByteArray? = null
    private var cachedX25519Private: ByteArray? = null
    private var cachedX25519Public: ByteArray? = null

    // =========================================================================
    // LocalKeyStore Interface Implementation
    // =========================================================================

    override fun isInitialized(): Boolean {
        return encryptedPrefs.getBoolean(KEY_INITIALIZED, false)
    }

    override fun initializeFromSeed(seed: ByteArray, accountIndex: Int) {
        require(seed.size == 64) { "Seed must be 64 bytes" }

        val derivation = FamilySafeKeyDerivation(seed)
        val keys = derivation.deriveKeysForAccount(accountIndex)

        // Store Ed25519 SEED (32 bytes), not the full 64-byte secret key
        storeKey(KEY_ED25519_SEED, keys.signingKeyPair.privateKey)
        storeKey(KEY_ED25519_PUBLIC, keys.signingKeyPair.publicKey)
        storeKey(KEY_X25519_PRIVATE, keys.encryptionKeyPair.privateKey)
        storeKey(KEY_X25519_PUBLIC, keys.encryptionKeyPair.publicKey)

        encryptedPrefs.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putInt(KEY_ACCOUNT_INDEX, accountIndex)
            .apply()

        // Update cache
        cachedEd25519Seed = keys.signingKeyPair.privateKey.copyOf()
        cachedEd25519Public = keys.signingKeyPair.publicKey.copyOf()
        cachedX25519Private = keys.encryptionKeyPair.privateKey.copyOf()
        cachedX25519Public = keys.encryptionKeyPair.publicKey.copyOf()

        // Securely zero the seed (best effort in JVM)
        seed.fill(0)
    }

    override fun getEd25519PrivateKey(): ByteArray {
        ensureInitialized()

        // Get the 32-byte seed
        val seed = cachedEd25519Seed?.copyOf()
            ?: loadKey(KEY_ED25519_SEED).also { cachedEd25519Seed = it.copyOf() }

        // Derive the full 64-byte secret key from the seed
        // This is what Lazysodium needs for signing operations
        return LazysodiumEd25519.getSecretKeyFromSeed(seed)
    }

    override fun getEd25519PublicKey(): ByteArray {
        ensureInitialized()
        return cachedEd25519Public?.copyOf()
            ?: loadKey(KEY_ED25519_PUBLIC).also { cachedEd25519Public = it.copyOf() }
    }

    override fun getX25519PrivateKey(): ByteArray {
        ensureInitialized()
        return cachedX25519Private?.copyOf()
            ?: loadKey(KEY_X25519_PRIVATE).also { cachedX25519Private = it.copyOf() }
    }

    override fun getX25519PublicKey(): ByteArray {
        ensureInitialized()
        return cachedX25519Public?.copyOf()
            ?: loadKey(KEY_X25519_PUBLIC).also { cachedX25519Public = it.copyOf() }
    }

    // =========================================================================
    // Key Storage Helpers
    // =========================================================================

    private fun ensureInitialized() {
        check(isInitialized()) {
            "KeyStore not initialized. Call initializeFromSeed() first."
        }
    }

    private fun storeKey(name: String, key: ByteArray) {
        // Keys are stored as hex in EncryptedSharedPreferences
        // EncryptedSharedPreferences handles the encryption automatically
        encryptedPrefs.edit()
            .putString(name, key.toHexString())
            .apply()
    }

    private fun loadKey(name: String): ByteArray {
        val hex = encryptedPrefs.getString(name, null)
            ?: throw IllegalStateException("Key $name not found in storage")
        return hex.hexToByteArray()
    }

    // =========================================================================
    // Mnemonic Storage
    // =========================================================================

    /**
     * Persist the BIP-39 recovery phrase alongside the derived keys so it can
     * be shown to the user from the Settings screen.  The words are stored as a
     * single space-separated string inside the same EncryptedSharedPreferences
     * file that holds the signing and encryption keys.
     */
    fun storeMnemonic(words: List<String>) {
        encryptedPrefs.edit()
            .putString(KEY_MNEMONIC, words.joinToString(" "))
            .apply()
    }

    /**
     * Retrieve the previously stored recovery phrase, or null if it was never
     * saved (e.g. the user set up the account before this feature was added).
     */
    fun getMnemonic(): List<String>? {
        val raw = encryptedPrefs.getString(KEY_MNEMONIC, null) ?: return null
        return raw.split(" ").filter { it.isNotBlank() }
    }

    // =========================================================================
    // Key Destruction
    // =========================================================================

    /**
     * Securely destroy all stored keys.
     * Used when leaving a family group or wiping device.
     */
    fun destroyKeys() {
        // Clear cached keys
        cachedEd25519Seed?.fill(0)
        cachedEd25519Public?.fill(0)
        cachedX25519Private?.fill(0)
        cachedX25519Public?.fill(0)

        cachedEd25519Seed = null
        cachedEd25519Public = null
        cachedX25519Private = null
        cachedX25519Public = null

        // Clear stored keys
        encryptedPrefs.edit().clear().apply()
    }

    /**
     * Delete the Android Keystore encryption key.
     * WARNING: This will make all stored keys unrecoverable.
     */
    private fun deleteKeystoreKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }
    }
}
