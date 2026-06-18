package com.example.familysafety.backup

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupPayload(
    val mnemonic: String,
    val groupDefinitionJson: String
)

@Serializable
private data class BackupFile(
    val version: Int = 1,
    val salt: String,       // base64
    val nonce: String,      // base64
    val ciphertext: String  // base64
)

/**
 * Encrypts and decrypts FamilySafety backup files.
 *
 * Format: JSON { version, salt, nonce, ciphertext } where the ciphertext is
 * AES-256-GCM over a JSON payload containing the mnemonic + group definition.
 * The key is derived from the user-supplied password via PBKDF2-SHA256.
 */
object BackupManager {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private const val CURRENT_VERSION = 1
    private const val PBKDF2_ITERATIONS = 200_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val SALT_BYTES = 32

    fun export(mnemonic: List<String>, groupDefinitionJson: String, password: String): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }

        val payload = BackupPayload(
            mnemonic = mnemonic.joinToString(" "),
            groupDefinitionJson = groupDefinitionJson
        )
        val plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        val backupFile = BackupFile(
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        )
        return json.encodeToString(backupFile).toByteArray(Charsets.UTF_8)
    }

    /**
     * @throws IllegalArgumentException if the backup format is unrecognised
     * @throws javax.crypto.BadPaddingException if the password is wrong
     */
    fun import(data: ByteArray, password: String): BackupPayload {
        val backupFile = json.decodeFromString<BackupFile>(String(data, Charsets.UTF_8))
        require(backupFile.version in 1..CURRENT_VERSION) { "Unsupported backup version: ${backupFile.version}" }

        val salt = Base64.decode(backupFile.salt, Base64.NO_WRAP)
        val nonce = Base64.decode(backupFile.nonce, Base64.NO_WRAP)
        val ciphertext = Base64.decode(backupFile.ciphertext, Base64.NO_WRAP)

        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val plaintext = cipher.doFinal(ciphertext)

        return json.decodeFromString(String(plaintext, Charsets.UTF_8))
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
