package com.example.familysafety.group

import java.security.MessageDigest

/**
 * Interface for cryptographic operations required by GroupStateManager.
 *
 * Key Architecture (per your requirements):
 * - Signing: Ed25519 at path m/44'/1984'/accountIndex'/0'
 * - Encryption: X25519 at path m/44'/1984'/accountIndex'/1'
 *
 * Keys are derived separately using SLIP-10, NOT converted via birational map.
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

    /**
     * Encrypt a message for a specific recipient using X25519 + XSalsa20-Poly1305 (NaCl box).
     *
     * @param plaintext The message to encrypt
     * @param recipientX25519PublicKeyHex Recipient's X25519 public key
     * @return Encrypted message (nonce prepended)
     */
    fun encryptForRecipient(
        plaintext: ByteArray,
        recipientX25519PublicKeyHex: String
    ): ByteArray

    /**
     * Decrypt a message sent to us using our X25519 private key.
     *
     * @param ciphertext The encrypted message (with prepended nonce)
     * @param senderX25519PublicKeyHex Sender's X25519 public key
     * @return Decrypted plaintext, or null if decryption fails
     */
    fun decryptFromSender(
        ciphertext: ByteArray,
        senderX25519PublicKeyHex: String
    ): ByteArray?
}

/**
 * Production implementation using TweetNaCl-java or Lazysodium.
 *
 * Key derivation follows SLIP-10 for Ed25519 from BIP-39 seed.
 *
 * IMPORTANT: This implementation requires one of:
 * - org.whispersystems:curve25519-java
 * - com.goterl:lazysodium-android
 * - Custom JNI bindings to libsodium
 */
class Slip10CryptoProvider(
    private val keyStore: LocalKeyStore
) : CryptoProvider {

    companion object {
        // SLIP-10 derivation path constants
        const val PURPOSE = 44
        const val COIN_TYPE = 1984
        const val KEY_TYPE_SIGNING = 0    // Ed25519
        const val KEY_TYPE_ENCRYPTION = 1  // X25519
    }

    /**
     * Interface for secure key storage.
     * Implementation should use Android Keystore or encrypted storage.
     */
    interface LocalKeyStore {
        /** Get the Ed25519 private key (32 bytes). */
        fun getEd25519PrivateKey(): ByteArray

        /** Get the Ed25519 public key (32 bytes). */
        fun getEd25519PublicKey(): ByteArray

        /** Get the X25519 private key (32 bytes). */
        fun getX25519PrivateKey(): ByteArray

        /** Get the X25519 public key (32 bytes). */
        fun getX25519PublicKey(): ByteArray

        /**
         * Initialize keys from a BIP-39 seed using SLIP-10 derivation.
         *
         * Derives two separate keys:
         * - m/44'/1984'/0'/0' → Ed25519 (signing)
         * - m/44'/1984'/0'/1' → X25519 (encryption)
         *
         * @param seed 64-byte seed from BIP-39 mnemonic
         * @param accountIndex Account index (default 0)
         */
        fun initializeFromSeed(seed: ByteArray, accountIndex: Int = 0)

        /** Check if keys have been initialized. */
        fun isInitialized(): Boolean
    }

    // =========================================================================
    // SIGNATURE OPERATIONS
    // =========================================================================

    override fun verifySignature(
        message: ByteArray,
        signature: ByteArray,
        publicKeyHex: String
    ): Boolean {
        require(signature.size == 64) { "Ed25519 signature must be 64 bytes" }

        val publicKey = publicKeyHex.hexToByteArray()
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }

        // Use TweetNaCl or Lazysodium for actual verification
        // Placeholder: return true for structure
        return Ed25519.verify(message, signature, publicKey)
    }

    override fun sign(message: ByteArray): ByteArray {
        val privateKey = keyStore.getEd25519PrivateKey()
        return Ed25519.sign(message, privateKey)
    }

    override fun getLocalEd25519PublicKey(): String {
        return keyStore.getEd25519PublicKey().toHexString()
    }

    override fun getLocalX25519PublicKey(): String {
        return keyStore.getX25519PublicKey().toHexString()
    }

    // =========================================================================
    // HASHING
    // =========================================================================

    override fun deriveMemberId(ed25519PublicKeyHex: String): String {
        val publicKey = ed25519PublicKeyHex.hexToByteArray()
        val hash = sha256(publicKey)
        // Take first 16 bytes (128 bits) for member ID
        return hash.take(16).toByteArray().toHexString()
    }

    override fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    // =========================================================================
    // ENCRYPTION (X25519 + XSalsa20-Poly1305)
    // =========================================================================

    override fun encryptForRecipient(
        plaintext: ByteArray,
        recipientX25519PublicKeyHex: String
    ): ByteArray {
        val recipientPublicKey = recipientX25519PublicKeyHex.hexToByteArray()
        val senderPrivateKey = keyStore.getX25519PrivateKey()

        // NaCl box: crypto_box(message, nonce, recipientPubKey, senderPrivKey)
        // Returns: nonce (24 bytes) || ciphertext
        return NaClBox.seal(plaintext, recipientPublicKey, senderPrivateKey)
    }

    override fun decryptFromSender(
        ciphertext: ByteArray,
        senderX25519PublicKeyHex: String
    ): ByteArray? {
        val senderPublicKey = senderX25519PublicKeyHex.hexToByteArray()
        val recipientPrivateKey = keyStore.getX25519PrivateKey()

        // NaCl box open: crypto_box_open(ciphertext, nonce, senderPubKey, recipientPrivKey)
        return NaClBox.open(ciphertext, senderPublicKey, recipientPrivateKey)
    }
}

// =========================================================================
// PLACEHOLDER CRYPTO PRIMITIVES (Replace with actual library)
// =========================================================================

/**
 * Placeholder for Ed25519 operations.
 * In production, use: com.goterl:lazysodium-android or org.whispersystems:curve25519-java
 */
internal object Ed25519 {
    fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        // TODO: Replace with actual Ed25519 implementation
        // Using Lazysodium: lazySodium.cryptoSignDetached(message, privateKey)
        throw NotImplementedError("Replace with Lazysodium or similar Ed25519 implementation")
    }

    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        // TODO: Replace with actual Ed25519 implementation
        // Using Lazysodium: lazySodium.cryptoSignVerifyDetached(signature, message, publicKey)
        throw NotImplementedError("Replace with Lazysodium or similar Ed25519 implementation")
    }
}

/**
 * Placeholder for NaCl Box (X25519 + XSalsa20-Poly1305).
 * In production, use: com.goterl:lazysodium-android
 */
internal object NaClBox {
    private const val NONCE_SIZE = 24

    fun seal(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): ByteArray {
        // TODO: Replace with actual NaCl box implementation
        // 1. Generate random 24-byte nonce
        // 2. Compute shared secret via X25519
        // 3. Encrypt with XSalsa20-Poly1305
        // 4. Return: nonce || ciphertext || auth_tag
        throw NotImplementedError("Replace with Lazysodium or TweetNaCl implementation")
    }

    fun open(
        ciphertext: ByteArray,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray? {
        // TODO: Replace with actual NaCl box_open implementation
        // 1. Extract nonce from first 24 bytes
        // 2. Compute shared secret via X25519
        // 3. Decrypt and verify with XSalsa20-Poly1305
        // 4. Return plaintext or null if auth fails
        throw NotImplementedError("Replace with Lazysodium or TweetNaCl implementation")
    }
}

// =========================================================================
// EXTENSION FUNCTIONS
// =========================================================================

/**
 * Convert hex string to ByteArray.
 */
fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

/**
 * Convert ByteArray to lowercase hex string.
 */
fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}

/**
 * Take first n elements and convert to ByteArray.
 */
fun List<Byte>.toByteArray(): ByteArray = ByteArray(this.size) { this[it] }