package com.example.familysafety.crypto

import com.example.familysafety.group.LazysodiumCryptoProvider
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.SecretBox
import com.goterl.lazysodium.utils.Key
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class E2EEManager @Inject constructor(
    private val cryptoProvider: LazysodiumCryptoProvider
) {
    private val lazySodium = LazySodiumAndroid(SodiumAndroid())
    private val sharedSecretCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    @Serializable
    data class EncryptedMessage(
        val senderMemberId: String,
        val nonce: String,
        val ciphertext: String,
        val signature: String
    )

    companion object {
        private val envelopeJson = Json { ignoreUnknownKeys = true }

        /**
         * Read the plaintext senderMemberId from an encrypted envelope without decrypting.
         * The value is unauthenticated until decryptMessage verifies the signature against
         * that sender's keys — treat it as routing metadata only.
         */
        fun peekSenderMemberId(encryptedMessageJson: String): String? = try {
            envelopeJson.decodeFromString<EncryptedMessage>(encryptedMessageJson).senderMemberId
        } catch (e: Exception) {
            null
        }
    }

    fun encryptMessage(
        plaintext: String,
        recipientMemberId: String,
        recipientX25519PublicKey: ByteArray
    ): String {
        val sharedSecret = getOrComputeSharedSecret(recipientMemberId, recipientX25519PublicKey)

        val nonce = lazySodium.randomBytesBuf(SecretBox.NONCEBYTES)
        
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertext = ByteArray(plaintextBytes.size + SecretBox.MACBYTES)

        val encryptSuccess = lazySodium.cryptoSecretBoxEasy(
            ciphertext,
            plaintextBytes,
            plaintextBytes.size.toLong(),
            nonce,
            sharedSecret
        )
        
        if (!encryptSuccess) {
            throw EncryptionException("Failed to encrypt message")
        }
        
        val dataToSign = nonce + ciphertext
        val signature = cryptoProvider.signMessage(dataToSign)
        
        val encryptedMessage = EncryptedMessage(
            senderMemberId = cryptoProvider.getMemberId(),
            nonce = nonce.toHexString(),
            ciphertext = ciphertext.toHexString(),
            signature = signature.toHexString()
        )
        
        return Json.encodeToString(encryptedMessage)
    }

    fun decryptMessage(
        encryptedMessageJson: String,
        senderX25519PublicKey: ByteArray,
        senderEd25519PublicKey: ByteArray
    ): String {
        val encryptedMessage = try {
            Json.decodeFromString<EncryptedMessage>(encryptedMessageJson)
        } catch (e: Exception) {
            throw EncryptionException("Failed to parse encrypted message: ${e.message}")
        }
        
        val nonce = encryptedMessage.nonce.hexToByteArray()
        val ciphertext = encryptedMessage.ciphertext.hexToByteArray()
        val signature = encryptedMessage.signature.hexToByteArray()
        
        val dataToVerify = nonce + ciphertext
        val isValidSignature = cryptoProvider.verifySignature(
            message = dataToVerify,
            signature = signature,
            publicKey = senderEd25519PublicKey
        )
        
        if (!isValidSignature) {
            throw EncryptionException("Invalid message signature")
        }
        
        val sharedSecret = getOrComputeSharedSecret(
            encryptedMessage.senderMemberId,
            senderX25519PublicKey
        )
        
        val plaintext = ByteArray(ciphertext.size - SecretBox.MACBYTES)

        val decryptSuccess = lazySodium.cryptoSecretBoxOpenEasy(
            plaintext,
            ciphertext,
            ciphertext.size.toLong(),
            nonce,
            sharedSecret
        )
        
        if (!decryptSuccess) {
            throw EncryptionException("Failed to decrypt message")
        }
        
        return String(plaintext, Charsets.UTF_8)
    }

    private fun getOrComputeSharedSecret(
        otherMemberId: String,
        otherX25519PublicKey: ByteArray
    ): ByteArray {
        sharedSecretCache[otherMemberId]?.let { return it }
        
        val myX25519PrivateKey = cryptoProvider.getX25519PrivateKey()
        val sharedSecret = ByteArray(Box.BEFORENMBYTES)
        
        val computeSuccess = lazySodium.cryptoBoxBeforeNm(
            sharedSecret,
            otherX25519PublicKey,
            myX25519PrivateKey
        )
        
        if (!computeSuccess) {
            throw EncryptionException("Failed to compute shared secret")
        }
        
        sharedSecretCache[otherMemberId] = sharedSecret
        
        return sharedSecret
    }

    fun clearSharedSecretCache() {
        sharedSecretCache.clear()
    }

    fun removeSharedSecret(memberId: String) {
        sharedSecretCache.remove(memberId)
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * Holds public keys for a family member.
 * Keys are stored as hex-encoded strings (matching FamilyMember).
 */
data class RecipientKeys(
    val x25519PublicKey: String,
    val ed25519PublicKey: String
) {
    /** Convert x25519 key to ByteArray for crypto operations */
    fun x25519PublicKeyBytes(): ByteArray =
        x25519PublicKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Convert ed25519 key to ByteArray for crypto operations */
    fun ed25519PublicKeyBytes(): ByteArray =
        ed25519PublicKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

class EncryptionException(message: String) : Exception(message)
