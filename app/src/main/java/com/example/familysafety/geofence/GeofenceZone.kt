package com.example.familysafety.geofence

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class GeofenceZone(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val watchedMemberIds: Set<String> = emptySet(), // empty = all members
    val alertOnEnter: Boolean = true,
    val alertOnExit: Boolean = true,
    val enterSoundUri: String? = null, // null = default notification sound
    val exitSoundUri: String? = null,
    val isEnabled: Boolean = true,
    val colorHue: Float = 120f
)
