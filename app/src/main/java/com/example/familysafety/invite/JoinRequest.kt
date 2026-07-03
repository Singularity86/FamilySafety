package com.example.familysafety.invite

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import java.util.Base64

/**
 * Custom serializer for ByteArray to handle JSON serialization
 */
@Serializer(forClass = ByteArray::class)
object ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)
    
    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }
    
    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.getDecoder().decode(decoder.decodeString())
    }
}

/**
 * Approval sent to a joiner after their join request is accepted.
 *
 * The GroupDefinition is encrypted to the joiner's X25519 key (taken from their
 * join request) and signed by the inviter's Ed25519 key inside the envelope.
 * The inviter's public keys ride alongside in plaintext so the joiner can
 * bootstrap trust: the Ed25519 key must hash to the inviterMemberId that was
 * embedded in the QR invite the joiner physically scanned.
 */
@Serializable
data class JoinApprovalMessage(
    /** Inviter's Ed25519 public key, hex — must hash to the invite's inviterMemberId. */
    val inviterEd25519PublicKey: String,
    /** Inviter's X25519 public key, hex — used to derive the decryption shared secret. */
    val inviterX25519PublicKey: String,
    /** E2EEManager.EncryptedMessage JSON wrapping the GroupDefinition JSON. */
    val encryptedGroupDefinition: String
)

/**
 * Rejection sent to a joiner on the same join_approval topic (retained, so an
 * offline joiner learns on reconnect). Authenticated the same way as
 * [JoinApprovalMessage]: the inviter's Ed25519 key must hash to the QR invite's
 * inviterMemberId, and the payload is E2EE-encrypted + signed. Field names are
 * deliberately distinct from JoinApprovalMessage so the two cannot decode as
 * each other.
 */
@Serializable
data class JoinRejectionMessage(
    val inviterEd25519PublicKey: String,
    val inviterX25519PublicKey: String,
    /** E2EEManager.EncryptedMessage JSON wrapping a [JoinRejectionPayload]. */
    val encryptedRejection: String
)

/**
 * Inner payload of a rejection. Binds the specific request and joiner so a
 * replayed rejection cannot cancel a future join attempt.
 */
@Serializable
data class JoinRejectionPayload(
    val requestId: String,
    val joinerMemberId: String,
    val timestamp: Long
)

/**
 * Data class representing a join request from a user wanting to join a family group
 */
@Serializable
data class JoinRequest(
    val requestId: String,
    val requesterId: String,
    val displayName: String,
    @Serializable(with = ByteArraySerializer::class)
    val ed25519PublicKey: ByteArray,
    @Serializable(with = ByteArraySerializer::class)
    val x25519PublicKey: ByteArray,
    val groupId: String,
    val timestampMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as JoinRequest

        if (requestId != other.requestId) return false
        if (requesterId != other.requesterId) return false
        if (displayName != other.displayName) return false
        if (!ed25519PublicKey.contentEquals(other.ed25519PublicKey)) return false
        if (!x25519PublicKey.contentEquals(other.x25519PublicKey)) return false
        if (groupId != other.groupId) return false
        if (timestampMs != other.timestampMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = requestId.hashCode()
        result = 31 * result + requesterId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + ed25519PublicKey.contentHashCode()
        result = 31 * result + x25519PublicKey.contentHashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}
