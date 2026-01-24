package com.example.familysafety.invite

import kotlinx.serialization.Serializable

@Serializable
data class JoinRequest(
    val requestId: String,
    val newMemberId: String,
    val newMemberDisplayName: String,
    val newMemberEd25519PublicKey: String,
    val newMemberX25519PublicKey: String,
    val groupId: String,
    val timestamp: Long
)

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

@Serializable
data class GroupUpdateAck(
    val groupId: String,
    val version: Int,
    val memberId: String,
    val timestamp: Long
)
