package com.example.familysafety.invite

import com.example.familysafety.group.GroupTransitionValidator
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Join v2: a join request that only the person who scanned the invite can make (F13).
 *
 * In v1 the request travelled as plaintext, retained on the inviter's topic. Anyone on the
 * broker could read it, or replace it with one carrying their own keys under the same name,
 * and the inviter, seeing a familiar name, would approve and encrypt the whole family to the
 * outsider. v2 closes that three ways:
 *
 * - the QR carries a one-time secret that never touches the broker, and the request carries
 *   an HMAC under it, so a request can only come from someone who saw the QR;
 * - the request is encrypted to the inviter's key (taken from the QR) and signed by the
 *   requester, so the broker sees neither the name nor the keys;
 * - both phones show a short fingerprint of the requester's keys for an optional check.
 *
 * Pure JVM: the platform crypto (envelope decryption, Ed25519) is passed in.
 */
object JoinV2 {

    /** The v2 fields added to the invite map; the v1 fields stay so old joiners still work. */
    const val FIELD_VERSION = "v"
    const val FIELD_INVITE_ID = "inviteId"
    const val FIELD_SECRET = "inviteSecret"
    const val FIELD_INVITER_ED25519 = "inviterEd25519"
    const val FIELD_INVITER_X25519 = "inviterX25519"
    const val FIELD_EXPIRES_AT = "expiresAt"
    const val VERSION = "2"

    /** An invite is good for a day: long enough to send a code by message, short enough to go stale. */
    const val INVITE_LIFETIME_MS = 24 * 60 * 60 * 1000L

    const val SECRET_BYTES = 32

    fun mac(secret: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(message)
        }

    /**
     * Six digits both phones show for the requester's keys. SHA-256 of the raw Ed25519 then
     * X25519 key, first four bytes as a big-endian unsigned number, mod 1,000,000.
     */
    fun fingerprint(ed25519: ByteArray, x25519: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(ed25519 + x25519)
        val n = ((digest[0].toLong() and 0xff) shl 24) or
            ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or
            (digest[3].toLong() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    internal fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "odd-length hex" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/** The v2 part of an invite, as the joiner reads it from the QR. */
data class ScannedInviteV2(
    val inviteId: String,
    val secret: ByteArray,
    val inviterEd25519Hex: String,
    val inviterX25519Hex: String,
    val expiresAt: Long
) {
    companion object {
        /**
         * Null for a v1 invite (an older inviter), which gets a v1 request.
         *
         * @throws IllegalArgumentException for a v2 invite that is incomplete, expired, or whose
         *   keys aren't the inviter's. Its message starts "Invalid invite code" so it reaches the
         *   user as-is.
         */
        fun from(inviteData: Map<String, String>, inviterMemberId: String, now: Long): ScannedInviteV2? {
            if (inviteData[JoinV2.FIELD_VERSION] != JoinV2.VERSION) return null
            fun field(name: String) = inviteData[name]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Invalid invite code — missing $name")

            val ed = field(JoinV2.FIELD_INVITER_ED25519)
            // The QR is the root of trust: the keys it carries must be the inviter's, which is
            // what inviterMemberId (a hash of the signing key) pins.
            val derived = runCatching { GroupTransitionValidator.deriveMemberIdFromKey(ed) }.getOrNull()
            require(derived == inviterMemberId) { "Invalid invite code — the inviter's keys don't match" }

            val expiresAt = field(JoinV2.FIELD_EXPIRES_AT).toLongOrNull()
                ?: throw IllegalArgumentException("Invalid invite code — bad expiry")
            require(now <= expiresAt) { "Invalid invite code — this invite has expired. Ask for a new one." }

            val secret = runCatching { java.util.Base64.getDecoder().decode(field(JoinV2.FIELD_SECRET)) }
                .getOrNull()?.takeIf { it.size == JoinV2.SECRET_BYTES }
                ?: throw IllegalArgumentException("Invalid invite code — bad secret")

            return ScannedInviteV2(
                inviteId = field(JoinV2.FIELD_INVITE_ID),
                secret = secret,
                inviterEd25519Hex = ed,
                inviterX25519Hex = field(JoinV2.FIELD_INVITER_X25519),
                expiresAt = expiresAt
            )
        }
    }

    /**
     * The message to publish for [signedRequest], which must already carry [inviteId] and a
     * signature over its [JoinRequest.signedBytes].
     *
     * @param encryptToInviter wraps a plaintext in a §3 envelope to the inviter's X25519 key.
     */
    fun requestMessage(
        signedRequest: JoinRequest,
        encryptToInviter: (plaintext: String) -> String,
        encode: (JoinRequestV2Payload) -> String
    ): JoinRequestV2Message {
        require(signedRequest.inviteId == inviteId && signedRequest.signature != null)
        val mac = JoinV2.mac(secret, signedRequest.signedBytes())
        val payload = JoinRequestV2Payload(signedRequest, mac.joinToString("") { "%02x".format(it) })
        return JoinRequestV2Message(
            inviteId = inviteId,
            requesterEd25519 = signedRequest.ed25519PublicKey.joinToString("") { "%02x".format(it) },
            requesterX25519 = signedRequest.x25519PublicKey.joinToString("") { "%02x".format(it) },
            encryptedRequest = encryptToInviter(encode(payload))
        )
    }

    override fun equals(other: Any?) = other is ScannedInviteV2 && inviteId == other.inviteId
    override fun hashCode() = inviteId.hashCode()
}

/** What the inviter keeps for each invite it hands out. Never leaves the phone. */
@Serializable
data class PendingInvite(
    val inviteId: String,
    /** Base64 of the 32-byte secret. */
    val secret: String,
    val expiresAt: Long,
    val used: Boolean = false
)

/** The v2 request as published on the inviter's join_request topic. */
@Serializable
data class JoinRequestV2Message(
    val v: Int = 2,
    val inviteId: String,
    /** Hex. Needed to open the envelope; checked against the request inside it. */
    val requesterEd25519: String,
    val requesterX25519: String,
    /** §3 envelope from the requester to the inviter, wrapping a [JoinRequestV2Payload]. */
    val encryptedRequest: String
)

@Serializable
data class JoinRequestV2Payload(
    /** Carries [JoinRequest.inviteId], and a signature over [JoinRequest.signedBytes]. */
    val request: JoinRequest,
    /** Hex HMAC-SHA256 under the invite secret, over [JoinRequest.signedBytes]. */
    val mac: String
)

/** The inviter's checks on a v2 request, in the order the spec lists them. */
object JoinV2Verifier {

    sealed class Result {
        data class Verified(val request: JoinRequest) : Result()
        data class Rejected(val reason: String) : Result()
    }

    /**
     * @param findInvite the stored invite for an inviteId, or null.
     * @param openEnvelope decrypts the §3 envelope with the given requester keys, verifying
     *   its signature, or throws.
     * @param verifySignature Ed25519 check of (message, signature, publicKeyHex).
     */
    fun verify(
        message: JoinRequestV2Message,
        now: Long,
        findInvite: (inviteId: String) -> PendingInvite?,
        envelopeSender: (envelope: String) -> String?,
        openEnvelope: (envelope: String, x25519: ByteArray, ed25519: ByteArray) -> String,
        verifySignature: (message: ByteArray, signature: ByteArray, ed25519Hex: String) -> Boolean,
        decodePayload: (String) -> JoinRequestV2Payload
    ): Result = with(JoinV2) {
        val invite = findInvite(message.inviteId) ?: return Result.Rejected("unknown invite")
        if (invite.used) return Result.Rejected("invite already used")
        if (now > invite.expiresAt) return Result.Rejected("invite expired")

        val derivedId = try {
            GroupTransitionValidator.deriveMemberIdFromKey(message.requesterEd25519)
        } catch (e: Exception) {
            return Result.Rejected("signing key is not valid")
        }
        // Before opening it. The envelope names its sender in plaintext, and decryption caches
        // the shared secret under that name; an envelope naming an existing member but made
        // with someone else's key would leave that member's secret wrong in the cache.
        if (envelopeSender(message.encryptedRequest) != derivedId) {
            return Result.Rejected("envelope sender is not the requester's key")
        }

        val payload = try {
            decodePayload(
                openEnvelope(
                    message.encryptedRequest,
                    message.requesterX25519.hexToBytes(),
                    message.requesterEd25519.hexToBytes()
                )
            )
        } catch (e: Exception) {
            return Result.Rejected("request does not open with the keys it names")
        }
        val request = payload.request

        if (!request.ed25519PublicKey.toHex().equals(message.requesterEd25519, ignoreCase = true) ||
            !request.x25519PublicKey.toHex().equals(message.requesterX25519, ignoreCase = true)
        ) return Result.Rejected("keys inside the request differ from the keys outside")
        if (request.inviteId != message.inviteId) return Result.Rejected("invite mismatch")
        if (derivedId != request.requesterId) return Result.Rejected("member ID does not match the key")

        val signature = request.signature ?: return Result.Rejected("unsigned")
        val signed = request.signedBytes()
        val signatureOk = try {
            verifySignature(signed, signature.hexToBytes(), message.requesterEd25519)
        } catch (e: Exception) {
            false
        }
        if (!signatureOk) return Result.Rejected("signature does not verify")

        val expected = mac(java.util.Base64.getDecoder().decode(invite.secret), signed)
        val presented = try { payload.mac.hexToBytes() } catch (e: Exception) { ByteArray(0) }
        if (!MessageDigest.isEqual(expected, presented)) {
            return Result.Rejected("not made with this invite's secret")
        }

        Result.Verified(request.copy(verifiedByInvite = true))
    }
}
