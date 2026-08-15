package com.example.familysafety.group

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import timber.log.Timber
import java.io.File
import java.security.GeneralSecurityException
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
) : LocalKeyStore {

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
        } catch (e: GeneralSecurityException) {
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

        // If keys are gone, onboarding must be recalculated on next launch. commit(),
        // because "next launch" may well be a restart that kills this process first.
        context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove("onboarding_complete")
            .commit()
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

        // Written last and committed synchronously, so this flag can never be on disk
        // while the keys it vouches for are missing. isInitialized() is what onboarding
        // trusts to decide the user has an identity.
        encryptedPrefs.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putInt(KEY_ACCOUNT_INDEX, accountIndex)
            .commit()

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
        //
        // commit(), not apply(): onboarding finishes by restarting the process with
        // Process.killProcess, which is a hard kill that never runs the hooks that flush
        // apply()'s background write. A lost write here costs the user their identity —
        // and, next door in storeMnemonic, the only copy of their recovery phrase. These
        // run once during onboarding, so blocking on the disk write is free.
        encryptedPrefs.edit()
            .putString(name, key.toHexString())
            .commit()
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
        // commit(): the user has just been shown these words and asked to confirm them.
        // Losing this write to the restart that follows would leave them believing they
        // hold a working recovery phrase for an identity that no longer has one.
        encryptedPrefs.edit()
            .putString(KEY_MNEMONIC, words.joinToString(" "))
            .commit()
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

        // Clear stored keys. commit() for the same reason the writes use it — leaving the
        // group also restarts the process, and a wipe that does not reach disk leaves the
        // old identity behind for the next launch to pick up.
        val cleared = runCatching { encryptedPrefs.edit().clear().commit() }.getOrDefault(false)

        // Verify, do not assume. Leaving the family is presented to the user as erasing this
        // device's identity, and the caller wraps this in a silent catch — so a failure here
        // used to leave the signing keys and, worse, the BIP-39 recovery phrase sitting in
        // storage while the app showed the welcome screen as though everything was gone.
        // Confirmed on a real device on 2026-08-14: after leaving, all seven identity entries
        // were still present.
        //
        // If the normal clear did not take, delete the backing file outright. The keys are
        // meant to be unrecoverable at this point, so there is nothing to preserve.
        if (!cleared || isInitialized()) {
            Timber.w("destroyKeys: clear did not take, deleting the keystore file")
            runCatching { context.deleteSharedPreferences(PREFS_NAME) }
            runCatching { File(context.dataDir, "shared_prefs/$PREFS_NAME.xml").delete() }
            runCatching { deleteKeystoreKey() }
        }
    }

    /**
     * True when no identity remains in storage. Used to confirm a wipe actually happened
     * rather than trusting that it did.
     */
    fun isFullyDestroyed(): Boolean = runCatching { !isInitialized() }.getOrDefault(false)

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
