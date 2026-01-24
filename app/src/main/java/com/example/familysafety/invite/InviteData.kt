package com.example.familysafety.invite

import kotlinx.serialization.Serializable

@Serializable
data class InviteData(
    val groupId: String,
    val groupName: String,
    val inviterMemberId: String,
    val inviterDisplayName: String,
    val inviterEd25519PublicKey: String,
    val inviterX25519PublicKey: String,
    val timestamp: Long,
    val signature: String
)
