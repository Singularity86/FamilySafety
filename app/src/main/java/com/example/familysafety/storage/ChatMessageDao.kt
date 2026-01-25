package com.example.familysafety.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for chat messages.
 * Provides methods for storing and querying chat messages.
 */
@Dao
interface ChatMessageDao {

    // =========================================================================
    // INSERT OPERATIONS
    // =========================================================================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    @Update
    suspend fun update(message: ChatMessageEntity)

    // =========================================================================
    // QUERY - SINGLE CONVERSATION
    // =========================================================================

    /**
     * Get all messages in a conversation ordered by timestamp.
     */
    @Query("""
        SELECT * FROM chat_messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp ASC
    """)
    fun observeConversation(conversationId: String): Flow<List<ChatMessageEntity>>

    /**
     * Get messages in a conversation (non-reactive).
     */
    @Query("""
        SELECT * FROM chat_messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp ASC
    """)
    suspend fun getConversation(conversationId: String): List<ChatMessageEntity>

    /**
     * Get messages in a conversation with pagination.
     */
    @Query("""
        SELECT * FROM chat_messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<ChatMessageEntity>

    /**
     * Get the most recent message in a conversation.
     */
    @Query("""
        SELECT * FROM chat_messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getLastMessage(conversationId: String): ChatMessageEntity?

    /**
     * Get unread message count for a conversation.
     */
    @Query("""
        SELECT COUNT(*) FROM chat_messages
        WHERE conversationId = :conversationId
        AND isOutgoing = 0
        AND isReadLocally = 0
    """)
    suspend fun getUnreadCount(conversationId: String): Int

    /**
     * Observe unread count for a conversation.
     */
    @Query("""
        SELECT COUNT(*) FROM chat_messages
        WHERE conversationId = :conversationId
        AND isOutgoing = 0
        AND isReadLocally = 0
    """)
    fun observeUnreadCount(conversationId: String): Flow<Int>

    // =========================================================================
    // QUERY - CONVERSATION LIST
    // =========================================================================

    /**
     * Get all unique conversation IDs.
     */
    @Query("SELECT DISTINCT conversationId FROM chat_messages ORDER BY timestamp DESC")
    suspend fun getAllConversationIds(): List<String>

    /**
     * Observe all unique conversation IDs.
     */
    @Query("SELECT DISTINCT conversationId FROM chat_messages ORDER BY timestamp DESC")
    fun observeAllConversationIds(): Flow<List<String>>

    /**
     * Get total unread message count across all conversations.
     */
    @Query("""
        SELECT COUNT(*) FROM chat_messages
        WHERE isOutgoing = 0 AND isReadLocally = 0
    """)
    suspend fun getTotalUnreadCount(): Int

    /**
     * Observe total unread count.
     */
    @Query("""
        SELECT COUNT(*) FROM chat_messages
        WHERE isOutgoing = 0 AND isReadLocally = 0
    """)
    fun observeTotalUnreadCount(): Flow<Int>

    // =========================================================================
    // QUERY - BY MESSAGE ID
    // =========================================================================

    /**
     * Get a specific message by ID.
     */
    @Query("SELECT * FROM chat_messages WHERE messageId = :messageId")
    suspend fun getMessageById(messageId: String): ChatMessageEntity?

    /**
     * Check if a message exists (for deduplication).
     */
    @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE messageId = :messageId LIMIT 1)")
    suspend fun messageExists(messageId: String): Boolean

    // =========================================================================
    // QUERY - REPLICATION SUPPORT
    // =========================================================================

    /**
     * Get all messages in a conversation after a specific timestamp.
     * Used for sync: "give me everything newer than X"
     */
    @Query("""
        SELECT * FROM chat_messages
        WHERE conversationId = :conversationId
        AND timestamp > :afterTimestamp
        ORDER BY timestamp ASC
    """)
    suspend fun getMessagesAfter(conversationId: String, afterTimestamp: Long): List<ChatMessageEntity>

    /**
     * Get newest timestamp in a conversation.
     */
    @Query("SELECT MAX(timestamp) FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun getNewestTimestamp(conversationId: String): Long?

    /**
     * Get all messages for a specific sender after a timestamp.
     */
    @Query("""
        SELECT * FROM chat_messages
        WHERE senderId = :senderId
        AND timestamp > :afterTimestamp
        ORDER BY timestamp ASC
    """)
    suspend fun getMessagesBySenderAfter(senderId: String, afterTimestamp: Long): List<ChatMessageEntity>

    // =========================================================================
    // UPDATE OPERATIONS
    // =========================================================================

    /**
     * Update message status.
     */
    @Query("UPDATE chat_messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: MessageStatus)

    /**
     * Mark all messages in a conversation as read.
     */
    @Query("""
        UPDATE chat_messages
        SET isReadLocally = 1
        WHERE conversationId = :conversationId
        AND isOutgoing = 0
        AND isReadLocally = 0
    """)
    suspend fun markConversationAsRead(conversationId: String): Int

    /**
     * Mark a specific message as read.
     */
    @Query("UPDATE chat_messages SET isReadLocally = 1 WHERE messageId = :messageId")
    suspend fun markAsRead(messageId: String)

    // =========================================================================
    // DELETE OPERATIONS
    // =========================================================================

    /**
     * Delete a specific message.
     */
    @Query("DELETE FROM chat_messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    /**
     * Delete all messages in a conversation.
     */
    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    /**
     * Delete all messages for a member (when they leave the group).
     */
    @Query("DELETE FROM chat_messages WHERE senderId = :memberId OR recipientId = :memberId")
    suspend fun deleteAllForMember(memberId: String)

    /**
     * Delete messages older than a specific timestamp (retention policy).
     */
    @Query("DELETE FROM chat_messages WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long): Int

    /**
     * Delete all chat messages.
     */
    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()

    // =========================================================================
    // PENDING MESSAGE OPERATIONS
    // =========================================================================

    /**
     * Get all pending (unsent) messages.
     */
    @Query("SELECT * FROM chat_messages WHERE status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun getPendingMessages(): List<ChatMessageEntity>

    /**
     * Get all failed messages for retry.
     */
    @Query("SELECT * FROM chat_messages WHERE status = 'FAILED' ORDER BY timestamp ASC")
    suspend fun getFailedMessages(): List<ChatMessageEntity>

    // =========================================================================
    // STATISTICS
    // =========================================================================

    /**
     * Get total message count.
     */
    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun getTotalCount(): Int

    /**
     * Get message count per conversation.
     */
    @Query("SELECT conversationId, COUNT(*) as count FROM chat_messages GROUP BY conversationId")
    suspend fun getCountPerConversation(): List<ConversationMessageCount>
}

/**
 * Helper class for count per conversation query.
 */
data class ConversationMessageCount(
    val conversationId: String,
    val count: Int
)
