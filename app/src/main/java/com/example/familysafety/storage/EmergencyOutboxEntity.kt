package com.example.familysafety.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One crash alert still owed to one family member.
 *
 * A crash alert used to ride ordinary chat: sent once, and if that failed (no signal at a
 * crash site is the normal case), retried by nothing, because the chat retry skipped group
 * messages. A row here survives the app being killed and is retried until that member's
 * phone confirms delivery, or until [EmergencyRetrySchedule.GIVE_UP_AFTER_MS].
 *
 * Holds the alert text so it can be re-sent verbatim; the database is SQLCipher-encrypted.
 */
@Entity(
    tableName = "emergency_outbox",
    primaryKeys = ["alertId", "recipientId"],
    indices = [Index(value = ["nextAttemptAt"])]
)
data class EmergencyOutboxEntity(
    /** The chat messageId. Every retry reuses it, so receivers keep one copy. */
    val alertId: String,
    val recipientId: String,
    val groupId: String,
    val content: String,
    /** The alert's own time, sent unchanged on every retry. */
    val timestamp: Long,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    /** Set when this member's DELIVERED or READ receipt arrives; retries stop. */
    val deliveredAt: Long? = null
)

@Dao
interface EmergencyOutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<EmergencyOutboxEntity>)

    /** Undelivered rows whose next attempt is due and which haven't been given up on. */
    @Query(
        "SELECT * FROM emergency_outbox WHERE deliveredAt IS NULL " +
            "AND nextAttemptAt <= :now AND createdAt > :notBefore ORDER BY createdAt"
    )
    suspend fun due(now: Long, notBefore: Long): List<EmergencyOutboxEntity>

    /** The earliest pending attempt, so a retry loop knows how long to sleep. */
    @Query(
        "SELECT MIN(nextAttemptAt) FROM emergency_outbox WHERE deliveredAt IS NULL " +
            "AND createdAt > :notBefore"
    )
    suspend fun nextAttemptAt(notBefore: Long): Long?

    @Query(
        "UPDATE emergency_outbox SET attemptCount = attemptCount + 1, lastAttemptAt = :at, " +
            "nextAttemptAt = :nextAt, lastError = :error " +
            "WHERE alertId = :alertId AND recipientId = :recipientId"
    )
    suspend fun recordAttempt(alertId: String, recipientId: String, at: Long, nextAt: Long, error: String?)

    /** @return rows changed: 0 when this receipt isn't for an outstanding alert. */
    @Query(
        "UPDATE emergency_outbox SET deliveredAt = :at " +
            "WHERE alertId = :alertId AND recipientId = :recipientId AND deliveredAt IS NULL"
    )
    suspend fun markDelivered(alertId: String, recipientId: String, at: Long): Int

    @Query("SELECT * FROM emergency_outbox WHERE alertId = :alertId")
    fun observeAlert(alertId: String): Flow<List<EmergencyOutboxEntity>>

    @Query("DELETE FROM emergency_outbox WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

/** When a crash alert is retried. Kept apart from the database so it can be tested. */
object EmergencyRetrySchedule {
    /** After this, stop: a day-old crash alert is no longer news anyone can act on. */
    const val GIVE_UP_AFTER_MS = 24 * 60 * 60 * 1000L

    private val delaysMs = longArrayOf(15_000, 30_000, 60_000)

    /** Delay before the next attempt, after [attemptsMade] attempts: 15 s, 30 s, then every 60 s. */
    fun delayAfter(attemptsMade: Int): Long =
        delaysMs[(attemptsMade - 1).coerceIn(0, delaysMs.size - 1)]
}
