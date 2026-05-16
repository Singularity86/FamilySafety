package com.example.familysafety.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.familysafety.location.MemberLocation

/**
 * Durable outbox entry for a location snapshot that still needs to be published.
 */
@Entity(
    tableName = "pending_location_publishes",
    indices = [
        Index(value = ["memberId"]),
        Index(value = ["timestamp"]),
        Index(value = ["memberId", "timestamp"])
    ]
)
data class PendingLocationPublishEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memberId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val speed: Float? = null,
    val bearing: Float? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null
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
        fun fromMemberLocation(location: MemberLocation): PendingLocationPublishEntity =
            PendingLocationPublishEntity(
                memberId = location.memberId,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                timestamp = location.timestamp,
                speed = location.speed,
                bearing = location.bearing
            )
    }
}
