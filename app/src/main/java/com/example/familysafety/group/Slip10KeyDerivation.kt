package com.example.familysafety.group

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SLIP-10 Key Derivation for Ed25519 and X25519.
 *
 * Reference: https://github.com/satoshilabs/slips/blob/master/slip-0010.md
 *
 * SLIP-10 differs from BIP-32 in that:
 * 1. Uses HMAC-SHA512 with curve-specific keys
 * 2. Ed25519 only supports hardened derivation (all indices must have 0x80000000 bit set)
 * 3. Private key is the raw 32-byte scalar, not modified
 *
 * Path Format: m / purpose' / coin_type' / account' / key_type'
 *
 * For FamilySafe:
 *   m/44'/1984'/0'/0' → Ed25519 signing key
 *   m/44'/1984'/0'/1' → X25519 encryption key
 */
object Slip10 {

    private const val ED25519_SEED_KEY = "ed25519 seed"
    private const val HARDENED_OFFSET = 0x80000000.toInt()

    /**
     * Derive an Ed25519 key pair from a BIP-39 seed.
     *
     * @param seed 64-byte seed from BIP-39 mnemonic (PBKDF2 output)
     * @param path Derivation path as list of indices (all will be hardened)
     * @return Pair of (privateKey, publicKey), each 32 bytes
     */
    fun deriveEd25519(seed: ByteArray, path: List<Int>): KeyPair {
        require(seed.size == 64) { "Seed must be 64 bytes (512 bits)" }

        // Master key generation
        var (privateKey, chainCode) = generateMasterKey(seed)

        // Derive through path
        for (index in path) {
            val result = deriveChildKey(privateKey, chainCode, index or HARDENED_OFFSET)
            privateKey = result.first
            chainCode = result.second
        }

        // Generate public key from private key using Lazysodium
        val publicKey = LazysodiumEd25519.derivePublicKey(privateKey)

        return KeyPair(privateKey, publicKey)
    }

    /**
     * Derive an X25519 key pair from a BIP-39 seed.
     *
     * X25519 uses the same derivation as Ed25519, but the private key
     * undergoes clamping for X25519 operations.
     *
     * @param seed 64-byte seed from BIP-39 mnemonic
     * @param path Derivation path
     * @return Pair of (privateKey, publicKey), each 32 bytes
     */
    fun deriveX25519(seed: ByteArray, path: List<Int>): KeyPair {
        require(seed.size == 64) { "Seed must be 64 bytes" }

        // Same derivation as Ed25519
        var (privateKey, chainCode) = generateMasterKey(seed)

        for (index in path) {
            val result = deriveChildKey(privateKey, chainCode, index or HARDENED_OFFSET)
            privateKey = result.first
            chainCode = result.second
        }

        // Clamp private key for X25519
        val clampedPrivate = clampX25519PrivateKey(privateKey.copyOf())

        // Generate X25519 public key using Lazysodium
        val publicKey = LazysodiumX25519.derivePublicKey(clampedPrivate)

        return KeyPair(clampedPrivate, publicKey)
    }

    /**
     * Generate master key from seed using HMAC-SHA512.
     *
     * @return Pair of (privateKey, chainCode)
     */
    private fun generateMasterKey(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val hmac = hmacSha512(ED25519_SEED_KEY.toByteArray(), seed)

        val privateKey = hmac.copyOfRange(0, 32)
        val chainCode = hmac.copyOfRange(32, 64)

        return privateKey to chainCode
    }

    /**
     * Derive a child key at the given index.
     * Ed25519 only supports hardened derivation.
     *
     * @return Pair of (childPrivateKey, childChainCode)
     */
    private fun deriveChildKey(
        parentPrivateKey: ByteArray,
        parentChainCode: ByteArray,
        index: Int
    ): Pair<ByteArray, ByteArray> {
        require(index and HARDENED_OFFSET != 0) {
            "Ed25519 SLIP-10 only supports hardened derivation"
        }

        // Data = 0x00 || parent_private_key || index (4 bytes big-endian)
        val data = ByteArray(1 + 32 + 4)
        data[0] = 0x00
        System.arraycopy(parentPrivateKey, 0, data, 1, 32)
        data[33] = (index shr 24).toByte()
        data[34] = (index shr 16).toByte()
        data[35] = (index shr 8).toByte()
        data[36] = index.toByte()

        val hmac = hmacSha512(parentChainCode, data)

        val childPrivateKey = hmac.copyOfRange(0, 32)
        val childChainCode = hmac.copyOfRange(32, 64)

        return childPrivateKey to childChainCode
    }

    /**
     * HMAC-SHA512
     */
    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    /**
     * Clamp a 32-byte key for X25519 operations.
     *
     * Per RFC 7748:
     * - Clear bottom 3 bits of first byte
     * - Clear top bit of last byte
     * - Set second-to-top bit of last byte
     */
    private fun clampX25519PrivateKey(key: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }

        key[0] = (key[0].toInt() and 0xF8).toByte()
        key[31] = (key[31].toInt() and 0x7F).toByte()
        key[31] = (key[31].toInt() or 0x40).toByte()

        return key
    }

    /**
     * Key pair holder.
     */
    data class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyPair) return false
            return privateKey.contentEquals(other.privateKey) &&
                    publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int {
            var result = privateKey.contentHashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }
}

/**
 * Standard derivation paths for FamilySafe keys.
 */
object FamilySafeKeyPaths {
    private const val PURPOSE = 44
    private const val COIN_TYPE = 1984

    /**
     * Path for Ed25519 signing key: m/44'/1984'/account'/0'
     */
    fun signingKeyPath(accountIndex: Int = 0): List<Int> =
        listOf(PURPOSE, COIN_TYPE, accountIndex, 0)

    /**
     * Path for X25519 encryption key: m/44'/1984'/account'/1'
     */
    fun encryptionKeyPath(accountIndex: Int = 0): List<Int> =
        listOf(PURPOSE, COIN_TYPE, accountIndex, 1)
}

/**
 * Convenience class for deriving all FamilySafe keys from a single seed.
 */
class FamilySafeKeyDerivation(private val seed: ByteArray) {

    init {
        require(seed.size == 64) { "Seed must be 64 bytes from BIP-39" }
    }

    /**
     * Derive complete key set for a given account.
     */
    fun deriveKeysForAccount(accountIndex: Int = 0): FamilySafeKeys {
        val signingPath = FamilySafeKeyPaths.signingKeyPath(accountIndex)
        val encryptionPath = FamilySafeKeyPaths.encryptionKeyPath(accountIndex)

        val signingKeyPair = Slip10.deriveEd25519(seed, signingPath)
        val encryptionKeyPair = Slip10.deriveX25519(seed, encryptionPath)

        return FamilySafeKeys(
            signingKeyPair = signingKeyPair,
            encryptionKeyPair = encryptionKeyPair,
            accountIndex = accountIndex
        )
    }

    /**
     * Container for all derived keys.
     */
    data class FamilySafeKeys(
        val signingKeyPair: Slip10.KeyPair,
        val encryptionKeyPair: Slip10.KeyPair,
        val accountIndex: Int
    ) {
        val ed25519PublicKeyHex: String
            get() = signingKeyPair.publicKey.toHexString()

        val x25519PublicKeyHex: String
            get() = encryptionKeyPair.publicKey.toHexString()

        /**
         * Derive member ID from Ed25519 public key.
         * memberId = SHA-256(ed25519_pub_key).take(16).toHex()
         */
        val memberId: String by lazy {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(signingKeyPair.publicKey)
            hash.take(16).toByteArray().toHexString()
        }
    }
}
