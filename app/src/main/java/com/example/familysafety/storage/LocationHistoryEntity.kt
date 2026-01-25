package com.example.familysafety.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.familysafety.location.MemberLocation

/**
 * Persistent storage for location history.
 * Stores location data for ALL family members (not just self) to enable
 * cross-device backup and historical queries.
 *
 * Indexed by memberId and timestamp for efficient queries.
 */
@Entity(
    tableName = "location_history",
    indices = [
        Index(value = ["memberId"]),
        Index(value = ["timestamp"]),
        Index(value = ["memberId", "timestamp"])
    ]
)
data class LocationHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Member who reported this location */
    val memberId: String,

    /** Latitude in degrees */
    val latitude: Double,

    /** Longitude in degrees */
    val longitude: Double,

    /** Accuracy in meters */
    val accuracy: Float,

    /** Unix timestamp in milliseconds when location was recorded */
    val timestamp: Long,

    /** Speed in m/s (null if not available) */
    val speed: Float? = null,

    /** Bearing in degrees (null if not available) */
    val bearing: Float? = null,

    /** Whether this location was received from a peer (replicated) vs recorded locally */
    val isReplicated: Boolean = false,

    /** MemberId of the peer who sent this replicated data (null if local) */
    val replicatedFrom: String? = null,

    /** When this record was inserted into local database */
    val insertedAt: Long = System.currentTimeMillis()
) {
    fun toMemberLocation(): MemberLocation = MemberLocation(
        memberId = memberId,
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        timestamp = timestamp,
        speed = speed,
        bearing = bearing
    )

    companion object {
        fun fromMemberLocation(
            location: MemberLocation,
            isReplicated: Boolean = false,
            replicatedFrom: String? = null
        ): LocationHistoryEntity = LocationHistoryEntity(
            memberId = location.memberId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.timestamp,
            speed = location.speed,
            bearing = location.bearing,
            isReplicated = isReplicated,
            replicatedFrom = replicatedFrom
        )
    }
}
