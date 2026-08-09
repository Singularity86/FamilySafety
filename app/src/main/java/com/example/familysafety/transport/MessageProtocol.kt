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
     * Sizes every envelope is padded up to, in bytes. Chosen to sit above the natural
     * maximum for each type so the common case is always exactly one size — bucketing
     * alone is not enough, because a variation that straddles a bucket boundary still
     * leaks. Oversized messages round up to the next multiple rather than going out at
     * their true length.
     */
    private const val PRESENCE_PADDED_BYTES = 256
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
        val timestamp: Long = System.currentTimeMillis()
    )
    
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
    
    fun encodePresenceUpdate(memberId: String, isOnline: Boolean): String {
        val presenceUpdate = PresenceUpdate(
            memberId = memberId,
            isOnline = isOnline
        )
        
        val envelope = MessageEnvelope(
            type = "presence_update",
            payload = json.encodeToString(presenceUpdate)
        )

        // Presence is plaintext on the wire by necessity (an MQTT will cannot be
        // encrypted), so padding is the only thing hiding online from offline by size.
        return encodePadded(envelope, PRESENCE_PADDED_BYTES)
    }
    
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
