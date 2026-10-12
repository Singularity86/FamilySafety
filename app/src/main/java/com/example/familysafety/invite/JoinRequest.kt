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
    val timestampMs: Long,
    /**
     * Hex Ed25519 signature over [signingBytes], made with the private half of
     * [ed25519PublicKey]. Proves the requester holds that identity's keys, which is what lets a
     * phone restored from its recovery phrase back into its family without a prompt
     * ([RejoinPolicy]). Optional: requests from builds before 1.15.1 have none, and are always
     * shown to the inviter for a decision.
     */
    val signature: String? = null,
    /**
     * Set on a v2 request: the invite it answers ([JoinV2]). Null for v1. Also selects which
     * bytes [signature] covers; see [signedBytes].
     */
    val inviteId: String? = null,
    /**
     * True only after [JoinV2Verifier] has checked this request against its invite's secret.
     * Never sent or read over the wire; a v1 request is always false and is shown as
     * unverified.
     */
    @kotlinx.serialization.Transient
    val verifiedByInvite: Boolean = false
) {
    /**
     * The bytes [signature] covers in a v1 request. Every field except the display name,
     * which is cosmetic and ignored when the requester is already a member.
     */
    fun signingBytes(): ByteArray {
        val text = "JOIN:v1:$requestId:$requesterId:$groupId:$timestampMs:" +
            "${ed25519PublicKey.toHex()}:${x25519PublicKey.toHex()}"
        return text.toByteArray(Charsets.UTF_8)
    }

    /**
     * The bytes a v2 request's signature and MAC cover: v1's fields plus the invite it answers
     * and a hash of the display name, so neither can be swapped after signing.
     */
    fun signingBytesV2(): ByteArray {
        val id = requireNotNull(inviteId) { "not a v2 request" }
        val text = "JOIN:v2:$requestId:$requesterId:$groupId:$timestampMs:" +
            "${ed25519PublicKey.toHex()}:${x25519PublicKey.toHex()}:$id:${JoinV2.sha256Hex(displayName)}"
        return text.toByteArray(Charsets.UTF_8)
    }

    /** Whichever of [signingBytes] or [signingBytesV2] this request's signature covers. */
    fun signedBytes(): ByteArray = if (inviteId != null) signingBytesV2() else signingBytes()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

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
        if (signature != other.signature) return false
        if (inviteId != other.inviteId) return false

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
        result = 31 * result + (signature?.hashCode() ?: 0)
        result = 31 * result + (inviteId?.hashCode() ?: 0)
        return result
    }
}
