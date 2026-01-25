package com.example.familysafety.replication

import com.example.familysafety.location.MemberLocation
import com.example.familysafety.storage.ChatMessageEntity
import com.example.familysafety.storage.MessageStatus
import com.example.familysafety.storage.MessageType
import kotlinx.serialization.Serializable

/**
 * Types of data that can be replicated between devices.
 */
@Serializable
enum class ReplicationDataType {
    LOCATION_HISTORY,
    CHAT_MESSAGES
}

/**
 * Request for data from a peer.
 * Sent when this device needs data it doesn't have.
 */
@Serializable
data class ReplicationRequest(
    /** Unique request ID for tracking responses */
    val requestId: String,

    /** Who is making the request */
    val requesterId: String,

    /** Type of data being requested */
    val dataType: ReplicationDataType,

    /** For location data: which member's data we want */
    val targetMemberId: String? = null,

    /** For chat data: which conversation we want */
    val conversationId: String? = null,

    /** Timestamp of newest data we have (send us everything after this) */
    val afterTimestamp: Long,

    /** Maximum number of items to return */
    val limit: Int = 500,

    /** Request timestamp */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Response containing replicated data.
 */
@Serializable
data class ReplicationResponse(
    /** Request ID this is responding to */
    val requestId: String,

    /** Who is sending the data */
    val senderId: String,

    /** Type of data being sent */
    val dataType: ReplicationDataType,

    /** Location data (if dataType == LOCATION_HISTORY) */
    val locations: List<ReplicatedLocation>? = null,

    /** Chat messages (if dataType == CHAT_MESSAGES) */
    val messages: List<ReplicatedMessage>? = null,

    /** Whether there's more data available */
    val hasMore: Boolean = false,

    /** Timestamp of the newest item in this response */
    val newestTimestamp: Long? = null,

    /** Response timestamp */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Announcement of data availability.
 * Broadcast to group so peers know who has what data.
 */
@Serializable
data class DataAvailabilityAnnouncement(
    /** Who is making the announcement */
    val announcerId: String,

    /** Summary of available data per member */
    val locationDataSummary: List<MemberDataSummary>,

    /** Summary of available chat data per conversation */
    val chatDataSummary: List<ConversationDataSummary>,

    /** Announcement timestamp */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Summary of location data available for a member.
 */
@Serializable
data class MemberDataSummary(
    val memberId: String,
    val oldestTimestamp: Long,
    val newestTimestamp: Long,
    val count: Int
)

/**
 * Summary of chat data available for a conversation.
 */
@Serializable
data class ConversationDataSummary(
    val conversationId: String,
    val oldestTimestamp: Long,
    val newestTimestamp: Long,
    val count: Int
)

/**
 * Location data in replication format.
 */
@Serializable
data class ReplicatedLocation(
    val memberId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val speed: Float? = null,
    val bearing: Float? = null
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
        fun fromMemberLocation(location: MemberLocation): ReplicatedLocation =
            ReplicatedLocation(
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

/**
 * Chat message in replication format.
 */
@Serializable
data class ReplicatedMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String?,
    val content: String,
    val messageType: MessageType,
    val status: MessageStatus,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val replyToMessageId: String? = null
) {
    fun toChatMessageEntity(
        replicatedFrom: String
    ): ChatMessageEntity = ChatMessageEntity(
        messageId = messageId,
        conversationId = conversationId,
        senderId = senderId,
        recipientId = recipientId,
        content = content,
        messageType = messageType,
        status = status,
        timestamp = timestamp,
        isOutgoing = isOutgoing,
        isReadLocally = false,
        isReplicated = true,
        replicatedFrom = replicatedFrom,
        replyToMessageId = replyToMessageId
    )

    companion object {
        fun fromChatMessageEntity(entity: ChatMessageEntity): ReplicatedMessage =
            ReplicatedMessage(
                messageId = entity.messageId,
                conversationId = entity.conversationId,
                senderId = entity.senderId,
                recipientId = entity.recipientId,
                content = entity.content,
                messageType = entity.messageType,
                status = entity.status,
                timestamp = entity.timestamp,
                isOutgoing = entity.isOutgoing,
                replyToMessageId = entity.replyToMessageId
            )
    }
}

/**
 * Result of a replication operation.
 */
sealed class ReplicationResult {
    data class Success(
        val itemsReceived: Int,
        val hasMore: Boolean
    ) : ReplicationResult()

    data class Error(
        val message: String,
        val isRetryable: Boolean = true
    ) : ReplicationResult()

    object NoDataAvailable : ReplicationResult()
    object Timeout : ReplicationResult()
}
