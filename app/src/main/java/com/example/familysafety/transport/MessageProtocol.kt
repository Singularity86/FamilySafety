package com.example.familysafety.transport

import com.example.familysafety.location.MemberLocation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

object MessageProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Wire-format generation this build speaks.
     *
     * 1 — everything before the 1.12.x security series. Reported implicitly by senders
     *     that omit the field, since it did not exist then.
     * 2 — padded envelopes, signed and sealed presence, encrypted file manifests,
     *     versioned file keys.
     *
     * Three of those changes replaced a message shape rather than adding a field, so a
     * 1 and a 2 cannot see each other's files or presence at all. Previously that failure
     * was silent — the other person simply never appeared. Advertising the version lets
     * the UI say "they need to update" instead of showing nothing and letting the user
     * conclude the app is broken.
     *
     * Bump this only when a change breaks interoperability. Additive fields do not
     * warrant it; older peers ignore what they do not understand.
     */
    const val PROTOCOL_VERSION = 2
    
    /**
     * Sizes every envelope is padded up to, in bytes. Chosen to sit above the natural
     * maximum for each type so the common case is always exactly one size — bucketing
     * alone is not enough, because a variation that straddles a bucket boundary still
     * leaks. Oversized messages round up to the next multiple rather than going out at
     * their true length.
     */
    // 512 rather than 256: a signed presence update carries a 128-character hex signature,
    // which pushes the envelope past 256 and would otherwise split presence across two
    // buckets — signed at 512, unsigned at 256 — making signed and unsigned trivially
    // distinguishable and undoing half the point of padding.
    private const val PRESENCE_PADDED_BYTES = 512
    private const val LOCATION_PADDED_BYTES = 512

    /** Filler character. Must not need JSON escaping, or padding would not be 1:1. */
    private const val PAD_CHAR = "."

    @Serializable
    data class MessageEnvelope(
        val type: String,
        val payload: String,
        /**
         * Filler so the serialized envelope reaches a fixed length. Carries no meaning
         * and is ignored on receive.
         *
         * Without it, message length is a side channel that survives encryption. Presence
         * was observably 136 bytes when online and 137 when offline — "true" is four
         * characters and "false" is five — so anyone watching the broker could read
         * everyone's online state without decrypting anything. Location has the same
         * problem: speed and bearing are optional, so a moving device emits a measurably
         * longer payload than a stationary one.
         */
        val pad: String = ""
    )

    /**
     * Serialize [envelope] padded to a fixed size.
     *
     * Applied to the plaintext, so for encrypted messages the ciphertext inherits the
     * constant length. Padding after encryption would not help — the ciphertext length
     * already reflects the plaintext by then.
     */
    private fun encodePadded(envelope: MessageEnvelope, targetBytes: Int): String {
        val bare = json.encodeToString(envelope.copy(pad = ""))
        val bareBytes = bare.toByteArray(Charsets.UTF_8).size
        // Round up to a multiple of the target so a message that overflows still lands on
        // a fixed grid instead of revealing its exact size.
        val target = ((bareBytes + targetBytes - 1) / targetBytes) * targetBytes
        // PAD_CHAR is ASCII and escape-free, so N characters add exactly N bytes.
        return json.encodeToString(envelope.copy(pad = PAD_CHAR.repeat(target - bareBytes)))
    }
    
    @Serializable
    data class LocationUpdate(
        val memberId: String,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val timestamp: Long,
        val speed: Float? = null,
        val bearing: Float? = null
    )
    
    @Serializable
    data class PresenceUpdate(
        val memberId: String,
        val isOnline: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        /**
         * Ed25519 signature (hex) over [signingPayload], or null from senders predating
         * this field.
         *
         * Presence is the only message type not inside the encrypted envelope, because an
         * MQTT last-will cannot be encrypted. That left it forgeable: the receiver checked
         * that the payload's memberId matched the topic, but anyone able to publish to an
         * arbitrary topic satisfies that trivially by publishing to the victim's own
         * presence topic. A signature moves the check from "who claims to have sent this"
         * to "who could have produced it", which no broker ACL can do for us.
         */
        val signature: String? = null,
        /**
         * The sender's [PROTOCOL_VERSION]. Absent from builds predating it, which is
         * exactly the case worth detecting, so the default of 1 is the right reading
         * rather than a fallback.
         *
         * Deliberately outside the signed payload: including it would change the
         * canonical signing string and break verification against already-shipped
         * builds, and the worst a tampered value can do is produce a spurious "needs
         * update" prompt. Presence is sealed under the group key, so an outsider cannot
         * write one at all.
         */
        val protocolVersion: Int = 1
    )

    /**
     * Presence sealed under the group key, as published on the presence topic since
     * 1.12.5.
     *
     * Padding the envelope hid online/offline from message *length*, but the payload was
     * still plaintext JSON, so the relay could simply read `isOnline`. That is the
     * clearest behavioural signal the broker had: who is awake, and when they leave. It
     * cannot be encrypted per recipient, because the last-will is published by the broker
     * once the device is already gone — but it can be sealed under a key the whole group
     * already holds.
     */
    @Serializable
    data class SealedPresence(
        /** Schema marker; 2 = sealed under the group key. Absent shape = legacy plaintext. */
        val v: Int,
        /** Base64 of `nonce ‖ ciphertext ‖ tag` over the plaintext presence envelope. */
        val data: String
    )

    /**
     * Canonical bytes covered by [PresenceUpdate.signature].
     *
     * Built from the fields rather than the serialized JSON so that key order, whitespace
     * and any future additive field cannot change what was signed. Any cross-platform
     * implementation must produce these bytes exactly.
     */
    fun presenceSigningPayload(memberId: String, isOnline: Boolean, timestamp: Long): ByteArray =
        "presence:$memberId:$isOnline:$timestamp".toByteArray(Charsets.UTF_8)
    
    fun encodeLocationUpdate(location: MemberLocation): String {
        val locationUpdate = LocationUpdate(
            memberId = location.memberId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.timestamp,
            speed = location.speed,
            bearing = location.bearing
        )
        
        val envelope = MessageEnvelope(
            type = "location_update",
            payload = json.encodeToString(locationUpdate)
        )

        // Padded before encryption so every location ciphertext is the same length,
        // whether or not speed and bearing are present. Otherwise a longer payload
        // means "this device is moving".
        return encodePadded(envelope, LOCATION_PADDED_BYTES)
    }
    
    /**
     * @param sign produces an Ed25519 signature over the canonical payload. Null only for
     *   callers with no signing key available, which leaves the message forgeable.
     */
    fun encodePresenceUpdate(
        memberId: String,
        isOnline: Boolean,
        sign: ((ByteArray) -> ByteArray)? = null
    ): String {
        val timestamp = System.currentTimeMillis()
        val presenceUpdate = PresenceUpdate(
            memberId = memberId,
            isOnline = isOnline,
            timestamp = timestamp,
            signature = sign?.invoke(presenceSigningPayload(memberId, isOnline, timestamp))
                ?.joinToString("") { "%02x".format(it) },
            protocolVersion = PROTOCOL_VERSION
        )
        
        val envelope = MessageEnvelope(
            type = "presence_update",
            payload = json.encodeToString(presenceUpdate)
        )

        // Presence is plaintext on the wire by necessity (an MQTT will cannot be
        // encrypted), so padding is the only thing hiding online from offline by size.
        return encodePadded(envelope, PRESENCE_PADDED_BYTES)
    }
    
    fun encodeSealedPresence(sealed: SealedPresence): String = json.encodeToString(sealed)

    /**
     * @return the sealed wrapper, or null when [payload] is a legacy plaintext envelope.
     *   The two shapes have disjoint required fields, so this is unambiguous.
     */
    fun decodeSealedPresenceOrNull(payload: String): SealedPresence? =
        runCatching { json.decodeFromString<SealedPresence>(payload) }.getOrNull()

    fun decodeEnvelope(envelopeJson: String): MessageEnvelope {
        return json.decodeFromString(envelopeJson)
    }
    
    fun decodeLocationUpdate(payloadJson: String): LocationUpdate {
        return json.decodeFromString(payloadJson)
    }
    
    fun decodePresenceUpdate(payloadJson: String): PresenceUpdate {
        return json.decodeFromString(payloadJson)
    }
    
    fun locationUpdateToMemberLocation(locationUpdate: LocationUpdate): MemberLocation {
        return MemberLocation(
            memberId = locationUpdate.memberId,
            latitude = locationUpdate.latitude,
            longitude = locationUpdate.longitude,
            accuracy = locationUpdate.accuracy,
            timestamp = locationUpdate.timestamp,
            speed = locationUpdate.speed,
            bearing = locationUpdate.bearing
        )
    }
}
