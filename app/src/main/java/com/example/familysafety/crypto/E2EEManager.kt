package com.example.familysafety.crypto

import com.example.familysafety.core.hexToBytes
import com.example.familysafety.core.toHex
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.security.SecurityEventKind
import com.example.familysafety.security.SecurityRecorder
import com.goterl.lazysodium.LazySodiumAndroid
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
    private val cryptoProvider: LazysodiumCryptoProvider,
    private val replayGuard: ReplayGuard
) {
    private val lazySodium: LazySodiumAndroid get() = Sodium.instance
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
        /** Shared-secret cache key: the member and the X25519 key the secret was made with. */
        internal fun cacheKey(memberId: String, x25519PublicKey: ByteArray): String =
            "$memberId:" + x25519PublicKey.toHex()

        fun peekSenderMemberId(encryptedMessageJson: String): String? = try {
            envelopeJson.decodeFromString<EncryptedMessage>(encryptedMessageJson).senderMemberId
        } catch (e: Exception) {
            null
        }
    }

    /**
     * @param recipientEd25519Hex the recipient's roster key, if known; lets a recipient who has
     *   already sent us sequenced envelopes get them back before their presence is seen (3.4)
     */
    fun encryptMessage(
        plaintext: String,
        recipientMemberId: String,
        recipientX25519PublicKey: ByteArray,
        recipientEd25519Hex: String? = null
    ): String {
        val sharedSecret = getOrComputeSharedSecret(recipientMemberId, recipientX25519PublicKey)

        val nonce = lazySodium.randomBytesBuf(SecretBox.NONCEBYTES)

        // Sequenced only for a peer that can read it (3.4); older builds would fail to parse it.
        val body = if (replayGuard.sequenceFor(recipientMemberId, recipientEd25519Hex)) {
            replayGuard.seal(plaintext)
        } else plaintext
        val plaintextBytes = body.toByteArray(Charsets.UTF_8)
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
            nonce = nonce.toHex(),
            ciphertext = ciphertext.toHex(),
            signature = signature.toHex()
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
        
        val nonce = encryptedMessage.nonce.hexToBytes()
        val ciphertext = encryptedMessage.ciphertext.hexToBytes()
        val signature = encryptedMessage.signature.hexToBytes()
        
        val dataToVerify = nonce + ciphertext
        val isValidSignature = cryptoProvider.verifySignature(
            message = dataToVerify,
            signature = signature,
            publicKey = senderEd25519PublicKey
        )
        
        if (!isValidSignature) {
            // Only a roster member can be impersonated; a stranger's junk is logged where it's
            // first seen as a stranger.
            if (SecurityRecorder.isMember(encryptedMessage.senderMemberId)) {
                SecurityRecorder.record(SecurityEventKind.FORGED_SIGNATURE, encryptedMessage.senderMemberId, "envelope")
            }
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

        // Last, and only once the signature has verified: the window then moves for this sender
        // and key only. Throws ReplayedEnvelopeException for a copy already taken (3.4).
        return replayGuard.open(
            senderId = encryptedMessage.senderMemberId,
            senderEd25519Hex = senderEd25519PublicKey.toHex(),
            plaintext = String(plaintext, Charsets.UTF_8)
        )
    }

    private fun getOrComputeSharedSecret(
        otherMemberId: String,
        otherX25519PublicKey: ByteArray
    ): ByteArray {
        // Keyed by the key as well as the member. On decrypt, otherMemberId is the envelope's
        // plaintext senderMemberId, which nothing has authenticated yet. Keyed by ID alone, an
        // envelope naming a member but made with another key cached a wrong secret under that
        // member's ID, and their real messages stopped decrypting until the cache was cleared.
        val cacheKey = cacheKey(otherMemberId, otherX25519PublicKey)
        sharedSecretCache[cacheKey]?.let { return it }
        
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
        
        sharedSecretCache[cacheKey] = sharedSecret

        return sharedSecret
    }

    /**
     * The X25519 shared secret with a member (§3's `crypto_box_beforenm`), which keys the LAN
     * session handshake (4.1). Callers pass the key from the roster, never from a message.
     */
    fun sharedSecretWith(memberId: String, x25519PublicKey: ByteArray): ByteArray =
        getOrComputeSharedSecret(memberId, x25519PublicKey).copyOf()

    /** This phone's Ed25519 signature over [message], hex. For author signatures (F24). */
    fun signAsSelf(message: ByteArray): String = cryptoProvider.signMessage(message).toHex()

    /** Whether [signatureHex] is [ed25519Hex]'s signature over [message]. */
    fun verifyAuthor(message: ByteArray, signatureHex: String, ed25519Hex: String): Boolean =
        cryptoProvider.verifySignature(
            message = message,
            signature = signatureHex.hexToBytes(),
            publicKey = ed25519Hex.hexToBytes()
        )

    /** Whether envelopes to this member are sequenced, so control messages can go inside one (3.4). */
    fun peerSequences(memberId: String, ed25519Hex: String): Boolean =
        replayGuard.sequenceFor(memberId, ed25519Hex)

    /** Whether this member has sent us sequenced envelopes; their plaintext control messages are then forgeries. */
    fun hasSequenced(memberId: String, ed25519Hex: String): Boolean =
        replayGuard.hasSequenced(memberId, ed25519Hex)

    /** Whether this member advertises protocol 4, and so signs all the history they write (3.2). */
    fun advertisesProtocol4(memberId: String): Boolean = replayGuard.advertisesSequenced(memberId)

    fun clearSharedSecretCache() {
        sharedSecretCache.clear()
    }

    /** Every cached secret for [memberId], whichever key it was computed with. */
    fun removeSharedSecret(memberId: String) {
        sharedSecretCache.keys.removeIf { it.startsWith("$memberId:") }
    }


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
        x25519PublicKey.hexToBytes()

    /** Convert ed25519 key to ByteArray for crypto operations */
    fun ed25519PublicKeyBytes(): ByteArray =
        ed25519PublicKey.hexToBytes()
}

open class EncryptionException(message: String) : Exception(message)
