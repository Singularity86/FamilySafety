package com.example.familysafety.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FileAvailabilityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<FileAvailabilityEntity>)

    /**
     * Peers holding a complete copy, per file.
     *
     * Counts only COMPLETE, and only where the hash matches what the local row holds — a peer
     * with different bytes under the same id is not a copy, and counting it would report a
     * document as safe when it is not.
     */
    @Query("""
        SELECT a.fileId AS fileId, COUNT(*) AS copies
        FROM file_availability a
        INNER JOIN shared_files f ON f.fileId = a.fileId
        WHERE a.state = 'COMPLETE' AND a.contentHash = f.contentHash
        GROUP BY a.fileId
    """)
    fun observeCompleteCounts(): Flow<List<FileCopyCount>>

    /**
     * How many peers have ever told us what they hold.
     *
     * Zero means the mechanism is not running — every other device is on a build that does not
     * announce — not that nobody has a copy. The UI must not turn silence into an alarm.
     */
    @Query("SELECT COUNT(DISTINCT memberId) FROM file_availability")
    fun observeAnnouncingPeerCount(): Flow<Int>

    @Query("SELECT * FROM file_availability WHERE fileId = :fileId")
    suspend fun getForFile(fileId: String): List<FileAvailabilityEntity>

    /**
     * Peers that hold a complete copy of [fileId], freshest announcement first.
     * Used to ask a peer that can actually help rather than one taken in rotation.
     */
    @Query("""
        SELECT a.memberId FROM file_availability a
        INNER JOIN shared_files f ON f.fileId = a.fileId
        WHERE a.fileId = :fileId AND a.state = 'COMPLETE' AND a.contentHash = f.contentHash
        ORDER BY a.updatedAt DESC
    """)
    suspend fun getCompleteHolders(fileId: String): List<String>

    @Query("DELETE FROM file_availability WHERE fileId = :fileId")
    suspend fun deleteForFile(fileId: String)

    /** Drop rows for members who have left, so counts do not include departed devices. */
    @Query("DELETE FROM file_availability WHERE memberId NOT IN (:memberIds)")
    suspend fun deleteForMembersNotIn(memberIds: List<String>)

    @Query("DELETE FROM file_availability WHERE memberId = :memberId")
    suspend fun deleteForMember(memberId: String)

    @Query("DELETE FROM file_availability")
    suspend fun deleteAll()
}
