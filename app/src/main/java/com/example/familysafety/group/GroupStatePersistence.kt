package com.example.familysafety.group

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first


/**
 * Interface for persisting group state.
 * Implementations must ensure data-at-rest encryption.
 */
interface GroupStatePersistence {
    /**
     * Load the persisted group definition.
     * @return GroupDefinition if one exists, null otherwise
     * @throws Exception on storage errors
     */
    suspend fun loadGroupDefinition(): GroupDefinition?

    /**
     * Save a group definition, replacing any existing one.
     * Must be atomic - either fully succeeds or fully fails.
     * @throws Exception on storage errors
     */
    suspend fun saveGroupDefinition(definition: GroupDefinition)

    /**
     * Delete the persisted group definition (e.g., when leaving a group).
     */
    suspend fun deleteGroupDefinition()
}

/**
 * Encrypted DataStore-based persistence for group state.
 * Uses Android Keystore for key management (hardware-backed when available).
 *
 * Data is encrypted using AES-256-GCM with a key stored in Android Keystore.
 * The key is bound to the device and cannot be extracted.
 *
 * IMPORTANT: DataStore requires singleton pattern - only one instance per file.
 * Use the companion object's getInstance() method or Hilt injection.
 */
class EncryptedGroupStatePersistence private constructor(
    private val context: Context,
    private val dataStore: DataStore<EncryptedGroupState>
) : GroupStatePersistence {

    companion object {
        private const val DATASTORE_FILE_NAME = "group_state.pb"

        @Volatile
        private var INSTANCE: EncryptedGroupStatePersistence? = null

        @Volatile
        private var dataStoreInstance: DataStore<EncryptedGroupState>? = null

        /**
         * Get singleton instance of EncryptedGroupStatePersistence.
         * Thread-safe double-checked locking.
         */
        fun getInstance(context: Context): EncryptedGroupStatePersistence {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: createInstance(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun createInstance(context: Context): EncryptedGroupStatePersistence {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(false)
                .build()

            val dataStore = dataStoreInstance ?: synchronized(this) {
                dataStoreInstance ?: DataStoreFactory.create(
                    serializer = EncryptedGroupStateSerializer(masterKey),
                    produceFile = { context.dataStoreFile(DATASTORE_FILE_NAME) }
                ).also { dataStoreInstance = it }
            }

            return EncryptedGroupStatePersistence(context, dataStore)
        }

        /**
         * Clear singleton instance (for testing only).
         */
        fun clearInstance() {
            synchronized(this) {
                INSTANCE = null
                // Note: DataStore instance is NOT cleared - it must remain singleton
                // Tests should use deleteGroupDefinition() to clear data instead
            }
        }

        /**
         * For testing: completely reset including DataStore.
         * WARNING: Only use in @After test cleanup when no operations are pending.
         */
        fun resetForTesting() {
            synchronized(this) {
                INSTANCE = null
                dataStoreInstance = null
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun loadGroupDefinition(): GroupDefinition? {
        return withContext(Dispatchers.IO) {
            try {
                val encryptedState = dataStore.data.first()

                if (encryptedState.encryptedJson.isEmpty()) {
                    return@withContext null
                }

                val decryptedJson = decrypt(
                    encryptedState.encryptedJson,
                    encryptedState.iv
                )

                json.decodeFromString<GroupDefinition>(decryptedJson)
            } catch (e: Exception) {
                // Log error but don't crash - treat as no persisted state
                null
            }
        }
    }

    override suspend fun saveGroupDefinition(definition: GroupDefinition) {
        withContext(Dispatchers.IO) {
            val plainJson = json.encodeToString(definition)
            val (encrypted, iv) = encrypt(plainJson)

            dataStore.updateData { _ ->
                EncryptedGroupState(
                    encryptedJson = encrypted,
                    iv = iv,
                    version = definition.version
                )
            }
        }
    }

    override suspend fun deleteGroupDefinition() {
        withContext(Dispatchers.IO) {
            dataStore.updateData { _ ->
                EncryptedGroupState(
                    encryptedJson = ByteArray(0),
                    iv = ByteArray(0),
                    version = 0
                )
            }
        }
    }

    // =========================================================================
    // ENCRYPTION HELPERS
    // =========================================================================

    private fun encrypt(plaintext: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = getSecretKeyFromKeystore()
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)

        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return encrypted to iv
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = getSecretKeyFromKeystore()
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun getSecretKeyFromKeystore(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val alias = "familysafe_group_state_key"

        return if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry
            entry.secretKey
        } else {
            // Generate new key
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

            val keyGenSpec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()
        }
    }
}

/**
 * Internal data class for encrypted storage format.
 */
data class EncryptedGroupState(
    val encryptedJson: ByteArray,
    val iv: ByteArray,
    val version: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedGroupState) return false
        return encryptedJson.contentEquals(other.encryptedJson) &&
                iv.contentEquals(other.iv) &&
                version == other.version
    }

    override fun hashCode(): Int {
        var result = encryptedJson.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + version.hashCode()
        return result
    }
}

/**
 * DataStore Serializer for EncryptedGroupState.
 */
class EncryptedGroupStateSerializer(
    private val masterKey: MasterKey
) : Serializer<EncryptedGroupState> {

    override val defaultValue: EncryptedGroupState = EncryptedGroupState(
        encryptedJson = ByteArray(0),
        iv = ByteArray(0),
        version = 0
    )

    override suspend fun readFrom(input: InputStream): EncryptedGroupState {
        return try {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return defaultValue

            // Format: [4 bytes iv length][iv][4 bytes data length][data][8 bytes version]
            val buffer = ByteBuffer.wrap(bytes)

            val ivLength = buffer.int
            val iv = ByteArray(ivLength)
            buffer.get(iv)

            val dataLength = buffer.int
            val encryptedJson = ByteArray(dataLength)
            buffer.get(encryptedJson)

            val version = buffer.long

            EncryptedGroupState(encryptedJson, iv, version)
        } catch (e: Exception) {
            defaultValue
        }
    }

    override suspend fun writeTo(t: EncryptedGroupState, output: OutputStream) {
        val buffer = ByteBuffer.allocate(
            4 + t.iv.size + 4 + t.encryptedJson.size + 8
        )

        buffer.putInt(t.iv.size)
        buffer.put(t.iv)
        buffer.putInt(t.encryptedJson.size)
        buffer.put(t.encryptedJson)
        buffer.putLong(t.version)

        output.write(buffer.array())
    }
}
