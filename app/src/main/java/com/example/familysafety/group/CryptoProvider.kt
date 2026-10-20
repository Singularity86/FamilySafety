package com.example.familysafety.group

import com.example.familysafety.core.toHex

/**
 * Interface for cryptographic operations required by GroupStateManager.
 *
 * Key architecture:
 * - Signing: Ed25519 at path m/44'/1984'/accountIndex'/0'
 * - Encryption: X25519 at path m/44'/1984'/accountIndex'/1'
 *
 * Keys are derived separately using SLIP-10, NOT converted via birational map.
 * Production implementation: [LazysodiumCryptoProvider] (libsodium via Lazysodium).
 */
interface CryptoProvider {

    /**
     * Verify an Ed25519 signature.
     *
     * @param message The original message bytes that were signed
     * @param signature 64-byte Ed25519 signature
     * @param publicKeyHex 32-byte Ed25519 public key (hex-encoded)
     * @return true if signature is valid
     */
    fun verifySignature(
        message: ByteArray,
        signature: ByteArray,
        publicKeyHex: String
    ): Boolean

    /**
     * Sign a message with the local device's Ed25519 private key.
     *
     * @param message The message bytes to sign
     * @return 64-byte Ed25519 signature
     */
    fun sign(message: ByteArray): ByteArray

    /**
     * Get the local device's Ed25519 public key.
     *
     * @return 32-byte public key, hex-encoded
     */
    fun getLocalEd25519PublicKey(): String

    /**
     * Get the local device's X25519 public key (for encryption).
     *
     * @return 32-byte public key, hex-encoded
     */
    fun getLocalX25519PublicKey(): String

    /**
     * Derive the member ID from an Ed25519 public key.
     * memberId = SHA-256(publicKey).take(16).toHex()
     *
     * @param ed25519PublicKeyHex The public key to derive from
     * @return 32-character hex string (16 bytes)
     */
    fun deriveMemberId(ed25519PublicKeyHex: String): String

    /**
     * Compute SHA-256 hash of input.
     */
    fun sha256(input: ByteArray): ByteArray

}

// =========================================================================
// EXTENSION FUNCTIONS
// =========================================================================

