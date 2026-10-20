package com.example.familysafety.group

import com.example.familysafety.core.hexToBytes
import com.example.familysafety.core.toHex
import com.example.familysafety.crypto.Sodium
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.utils.Key
import com.goterl.lazysodium.utils.KeyPair
import java.security.MessageDigest

/**
 * Real Lazysodium implementation of CryptoProvider.
 *
 * Uses:
 * - Ed25519 for signatures
 * - X25519 + XSalsa20-Poly1305 for encryption (NaCl box)
 * - SHA-256 for hashing
 */
class LazysodiumCryptoProvider(
    private val keyStore: LocalKeyStore
) : CryptoProvider {

    private val sodium: LazySodiumAndroid get() = Sodium.instance

    // =========================================================================
    // SIGNATURE OPERATIONS
    // =========================================================================

    override fun verifySignature(
        message: ByteArray,
        signature: ByteArray,
        publicKeyHex: String
    ): Boolean {
        require(signature.size == Sign.BYTES) {
            "Ed25519 signature must be ${Sign.BYTES} bytes"
        }

        val publicKey = publicKeyHex.hexToBytes()
        require(publicKey.size == Sign.PUBLICKEYBYTES) {
            "Ed25519 public key must be ${Sign.PUBLICKEYBYTES} bytes"
        }

        return sodium.cryptoSignVerifyDetached(
            signature,
            message,
            message.size,
            publicKey
        )
    }

    override fun sign(message: ByteArray): ByteArray {
        val privateKey = keyStore.getEd25519PrivateKey()
        val signature = ByteArray(Sign.BYTES)

        val success = sodium.cryptoSignDetached(
            signature,
            message,
            message.size.toLong(),
            privateKey
        )

        require(success) { "Signing failed" }
        return signature
    }

    override fun getLocalEd25519PublicKey(): String {
        return keyStore.getEd25519PublicKey().toHex()
    }

    override fun getLocalX25519PublicKey(): String {
        return keyStore.getX25519PublicKey().toHex()
    }
// =========================================================================
// METHODS FOR E2EEManager COMPATIBILITY
// =========================================================================

fun getMemberId(): String {
    // Derive member ID from Ed25519 public key
    return deriveMemberId(getLocalEd25519PublicKey())
}

fun signMessage(message: ByteArray): ByteArray {
    // Delegate to existing sign method
    return sign(message)
}

fun verifySignature(
    message: ByteArray,
    signature: ByteArray,
    publicKey: ByteArray
): Boolean {
    // Convert ByteArray to hex string and call existing method
    return verifySignature(message, signature, publicKey.toHex())
}

fun getEd25519PublicKey(): ByteArray {
    return keyStore.getEd25519PublicKey()
}

fun getX25519PublicKey(): ByteArray {
    return keyStore.getX25519PublicKey()
}

fun getX25519PrivateKey(): ByteArray {
    return keyStore.getX25519PrivateKey()
}
    // =========================================================================
    // HASHING
    // =========================================================================

    override fun deriveMemberId(ed25519PublicKeyHex: String): String {
        val publicKey = ed25519PublicKeyHex.hexToBytes()
        val hash = sha256(publicKey)
        // Take first 16 bytes (128 bits) for member ID
        return hash.take(16).toByteArray().toHex()
    }

    override fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }
}

/**
 * Real Ed25519 key derivation using Lazysodium.
 */
object LazysodiumEd25519 {
    private val sodium: LazySodiumAndroid get() = Sodium.instance

    /**
     * Derive Ed25519 public key from 32-byte private seed.
     *
     * In Ed25519:
     * - Private key is 64 bytes (32-byte seed + 32-byte public key)
     * - We only store the 32-byte seed
     * - Public key is derived via scalar multiplication
     */
    fun derivePublicKey(privateSeed: ByteArray): ByteArray {
        require(privateSeed.size == Sign.SEEDBYTES) {
            "Ed25519 seed must be ${Sign.SEEDBYTES} bytes"
        }

        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.SECRETKEYBYTES)

        sodium.cryptoSignSeedKeypair(publicKey, secretKey, privateSeed)

        return publicKey
    }

    /**
     * Get the full 64-byte secret key from 32-byte seed.
     * This is needed for signing operations.
     */
    fun getSecretKeyFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == Sign.SEEDBYTES) {
            "Ed25519 seed must be ${Sign.SEEDBYTES} bytes"
        }

        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.SECRETKEYBYTES)

        sodium.cryptoSignSeedKeypair(publicKey, secretKey, seed)

        return secretKey
    }
}

/**
 * Real X25519 key derivation using Lazysodium.
 */
object LazysodiumX25519 {
    private val sodium: LazySodiumAndroid get() = Sodium.instance

    /**
     * Derive X25519 public key from 32-byte private key.
     *
     * X25519 uses clamped scalars, so we need to ensure the private
     * key is properly formatted.
     */
    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == Box.SECRETKEYBYTES) {
            "X25519 private key must be ${Box.SECRETKEYBYTES} bytes"
        }

        val publicKey = ByteArray(Box.PUBLICKEYBYTES)

        // Lazysodium derives public from secret automatically
        sodium.cryptoScalarMultBase(publicKey, privateKey)

        return publicKey
    }
}
