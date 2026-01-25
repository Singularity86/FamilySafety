package com.example.familysafety.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Delivery status for chat messages.
 */
@Serializable
enum class MessageStatus {
    /** Message created locally, not yet sent */
    PENDING,

    /** Message sent to MQTT broker */
    SENT,

    /** Recipient confirmed receipt */
    DELIVERED,

    /** Recipient has read the message */
    READ,

    /** Message delivery failed */
    FAILED
}

/**
 * Type of chat message.
 */
@Serializable
enum class MessageType {
    /** Regular text message */
    TEXT,

    /** Location share (embedded location data) */
    LOCATION,

    /** System message (member joined, left, etc.) */
    SYSTEM
}

/**
 * Persistent storage for chat messages.
 * Messages are stored encrypted at rest via SQLCipher.
 * Plain text is decrypted on read and encrypted on write.
 *
 * Indexed by conversationId and timestamp for efficient queries.
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["senderId"]),
        Index(value = ["timestamp"]),
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["status"])
    ]
)
data class ChatMessageEntity(
    /**
     * Unique message ID (UUID).
     * Used for deduplication and delivery tracking.
     */
    @PrimaryKey
    val messageId: String = UUID.randomUUID().toString(),

    /**
     * Conversation ID - for 1:1 chats this is the sorted pair of memberIds.
     * Format: "memberId1:memberId2" where memberId1 < memberId2 lexicographically.
     * For group chat (future): would be the groupId.
     */
    val conversationId: String,

    /**
     * MemberId of the sender.
     */
    val senderId: String,

    /**
     * MemberId of the recipient (for 1:1 chats).
     * Null for group messages.
     */
    val recipientId: String?,

    /**
     * Message content (decrypted plain text).
     * For LOCATION type, this is JSON with lat/lon.
     */
    val content: String,

    /**
     * Type of message.
     */
    val messageType: MessageType = MessageType.TEXT,

    /**
     * Delivery status.
     */
    val status: MessageStatus = MessageStatus.PENDING,

    /**
     * Unix timestamp in milliseconds when message was created/sent.
     */
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * Whether this message was sent by the local user.
     */
    val isOutgoing: Boolean,

    /**
     * Whether this message has been read by the local user.
     */
    val isReadLocally: Boolean = false,

    /**
     * Whether this message was received from replication (peer backup).
     */
    val isReplicated: Boolean = false,

    /**
     * MemberId of peer who provided this replicated message.
     */
    val replicatedFrom: String? = null,

    /**
     * When this record was inserted into local database.
     */
    val insertedAt: Long = System.currentTimeMillis(),

    /**
     * Optional: reply to another message ID.
     */
    val replyToMessageId: String? = null
) {
    companion object {
        /**
         * Generate conversation ID for a 1:1 chat.
         * Ensures consistent ID regardless of who initiates.
         */
        fun generateConversationId(memberId1: String, memberId2: String): String {
            return if (memberId1 < memberId2) {
                "$memberId1:$memberId2"
            } else {
                "$memberId2:$memberId1"
            }
        }

        /**
         * Create a text message.
         */
        fun createTextMessage(
            senderId: String,
            recipientId: String,
            content: String,
            isOutgoing: Boolean
        ): ChatMessageEntity {
            return ChatMessageEntity(
                conversationId = generateConversationId(senderId, recipientId),
                senderId = senderId,
                recipientId = recipientId,
                content = content,
                messageType = MessageType.TEXT,
                status = if (isOutgoing) MessageStatus.PENDING else MessageStatus.DELIVERED,
                isOutgoing = isOutgoing,
                isReadLocally = isOutgoing // Outgoing messages are "read" by sender
            )
        }

        /**
         * Create a location share message.
         */
        fun createLocationMessage(
            senderId: String,
            recipientId: String,
            latitude: Double,
            longitude: Double,
            isOutgoing: Boolean
        ): ChatMessageEntity {
            val locationJson = """{"lat":$latitude,"lon":$longitude}"""
            return ChatMessageEntity(
                conversationId = generateConversationId(senderId, recipientId),
                senderId = senderId,
                recipientId = recipientId,
                content = locationJson,
                messageType = MessageType.LOCATION,
                status = if (isOutgoing) MessageStatus.PENDING else MessageStatus.DELIVERED,
                isOutgoing = isOutgoing,
                isReadLocally = isOutgoing
            )
        }

        /**
         * Create a system message.
         */
        fun createSystemMessage(
            conversationId: String,
            content: String
        ): ChatMessageEntity {
            return ChatMessageEntity(
                conversationId = conversationId,
                senderId = "system",
                recipientId = null,
                content = content,
                messageType = MessageType.SYSTEM,
                status = MessageStatus.DELIVERED,
                isOutgoing = false,
                isReadLocally = true
            )
        }
    }
}

/**
 * Summary of a conversation for the conversation list.
 */
data class ConversationSummary(
    val conversationId: String,
    val otherMemberId: String,
    val lastMessage: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val lastMessageType: MessageType
)
