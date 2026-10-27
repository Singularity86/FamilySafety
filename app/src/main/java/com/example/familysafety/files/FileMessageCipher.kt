package com.example.familysafety.files

import com.example.familysafety.crypto.GroupCipher
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupKeys
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals a file or vault control message (a repair request, a holdings announcement) as an
 * [EncryptedFileMessage] under the family's file subkey, and opens one.
 *
 * One implementation for both, so `files/repair` and `vault/repair` stay byte-compatible with
 * each other and with iOS. A family with no group key (created before 1.12.0) seals under the
 * legacy key, exactly as its files are.
 */
object FileMessageCipher {

    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val json = Json { ignoreUnknownKeys = true }

    fun seal(group: GroupDefinition?, groupId: String, plaintext: String): ByteArray? = runCatching {
        val kid = group?.let { GroupKeys.stampFor(it) }
        val keyHex = group?.let { GroupKeys.keyHexFor(it, kid) }
        val message = if (keyHex != null) {
            EncryptedFileMessage(
                keyVersion = FILE_KEY_VERSION_GROUP_SUBKEY,
                data = encrypt(plaintext, GroupCipher.deriveSubkey(keyHex, GroupCipher.PURPOSE_FILES)),
                kid = kid
            )
        } else {
            EncryptedFileMessage(keyVersion = FILE_KEY_VERSION_LEGACY, data = encrypt(plaintext, FileKeys.legacy(groupId)))
        }
        json.encodeToString(message).toByteArray()
    }.getOrNull()

    /** The plaintext, or null when [raw] isn't a sealed message or this phone can't read it. */
    fun open(group: GroupDefinition?, groupId: String, raw: String): String? {
        val wrapped = runCatching { json.decodeFromString<EncryptedFileMessage>(raw) }.getOrNull() ?: return null
        val key = if (wrapped.kid == null) {
            // Refuses the public legacy key in a family that has a group key; see FileKeys.
            FileKeys.forVersion(groupId, group?.fileEncryptionKey?.takeIf { it.isNotBlank() }, wrapped.keyVersion)
        } else {
            // A named key we don't hold is unreadable, never the legacy key.
            group?.let { GroupKeys.keyHexFor(it, wrapped.kid) }
                ?.let { FileKeys.forVersion(groupId, it, wrapped.keyVersion) }
        } ?: return null
        return runCatching { decrypt(Base64.getDecoder().decode(wrapped.data), key) }.getOrNull()
    }

    private fun encrypt(plaintext: String, key: ByteArray): String {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plaintext.toByteArray()))
    }

    private fun decrypt(data: ByteArray, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, data.copyOfRange(0, NONCE_BYTES))
        )
        return String(cipher.doFinal(data.copyOfRange(NONCE_BYTES, data.size)))
    }
}
