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
    
    @Serializable
    data class MessageEnvelope(
        val type: String,
        val payload: String
    )
    
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
        
        return json.encodeToString(envelope)
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
        
        return json.encodeToString(envelope)
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
