package com.example.familysafety.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric AES-256-GCM under the group's shared secret, for the few messages that cannot
 * use the per-recipient envelope.
 *
 * Presence is the motivating case: an MQTT last-will is published by the broker after the
 * device is gone, so it cannot be encrypted to each recipient at send time. It *can* be
 * sealed under a key the whole group already holds, which is enough to stop the relay
 * reading it.
 *
 * Every use derives a purpose-specific subkey rather than using the group key directly, so
 * that recovering one message type's key does not hand over the others.
 */
object GroupCipher {

    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** Purpose label for presence. Changing this silently breaks interop. */
    const val PURPOSE_PRESENCE = "presence"

    /**
     * Derive a 32-byte subkey for [purpose] from the hex-encoded group key.
     *
     * SHA-256 over `key ‖ purpose`. Not HKDF, but the input is already a uniformly random
     * 256-bit secret rather than a password, so a hash with domain separation is
     * sufficient here and is trivial to reimplement on another platform.
     */
    fun deriveSubkey(groupKeyHex: String, purpose: String): ByteArray {
        val keyBytes = groupKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.getInstance("SHA-256")
            .digest(keyBytes + purpose.toByteArray(Charsets.UTF_8))
    }

    /** @return `nonce ‖ ciphertext ‖ tag`, matching CryptoKit's `SealedBox.combined`. */
    fun seal(plaintext: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    /** Throws if the blob was not sealed under [key]; GCM authenticates, so this is a real check. */
    fun open(blob: ByteArray, key: ByteArray): ByteArray {
        require(blob.size > NONCE_BYTES) { "sealed blob is too short to contain a nonce" }
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(blob.copyOfRange(NONCE_BYTES, blob.size))
    }
}
