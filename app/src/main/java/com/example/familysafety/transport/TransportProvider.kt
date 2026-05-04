package com.example.familysafety.transport

/**
 * High-level interface for sending messages between family members.
 * Implementations should prioritize privacy and efficiency (e.g., P2P over Cloud).
 */
interface TransportProvider {
    /**
     * Send a message to a specific family member.
     * Implementation should try P2P (LAN) first and fall back to Cloud (MQTT).
     */
    suspend fun sendMessage(
        recipientId: String,
        topic: String,
        payload: ByteArray,
        qos: Int = 1,
        retained: Boolean = false
    ): Boolean

    /**
     * Broadcast a message to all members of the group.
     */
    suspend fun broadcastMessage(
        topic: String,
        payload: ByteArray,
        qos: Int = 1,
        retained: Boolean = false
    ): Boolean
}
