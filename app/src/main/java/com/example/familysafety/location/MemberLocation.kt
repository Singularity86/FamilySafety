package com.example.familysafety.location

@kotlinx.serialization.Serializable
data class MemberLocation(
    val memberId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val speed: Float? = null,
    val bearing: Float? = null
)
