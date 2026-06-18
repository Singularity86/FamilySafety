package com.example.familysafety.invite

import kotlinx.serialization.Serializable

@Serializable
data class JoinResponse(
    val requestId: String,
    val approved: Boolean,
    val reason: String? = null,
    val timestamp: Long
)

@Serializable
data class GroupUpdate(
    val groupId: String,
    val version: Int,
    val updatedMembers: List<String>,
    val timestamp: Long
)
