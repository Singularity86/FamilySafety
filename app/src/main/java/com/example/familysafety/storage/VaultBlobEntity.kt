package com.example.familysafety.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Chunk accounting for a vault document's bytes.
 *
 * Deliberately not a row in `shared_files`. That table feeds the manifest, the file grid, the
 * status board and the transfer log, and a vault document that appears in any of those is not
 * in a vault — the manifest in particular is readable by every member, so listing vault
 * documents there would tell the whole family how many a vault holds, which is precisely what
 * the container's fixed size exists to hide.
 *
 * What this table records is deliberately almost nothing: an id, how many chunks there are and
 * which ones arrived. No name, no type, no size in the user's sense, no owner, no hash. A
 * device that does not hold the code stores these blobs and this row and learns nothing from
 * either — it cannot even tell a vault blob from a partial download, and unreferenced blobs
 * already exist for that reason.
 */
@Entity(tableName = "vault_blobs")
data class VaultBlobEntity(
    @PrimaryKey val fileId: String,
    val chunkCount: Int,
    val chunksReceived: Int = 0,
    val chunkBitmap: ByteArray? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    // Room's generated equals would compare the bitmap by reference, which breaks any
    // comparison of two rows holding the same chunks.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultBlobEntity) return false
        return fileId == other.fileId &&
            chunkCount == other.chunkCount &&
            chunksReceived == other.chunksReceived &&
            updatedAt == other.updatedAt &&
            (chunkBitmap?.contentEquals(other.chunkBitmap) ?: (other.chunkBitmap == null))
    }

    override fun hashCode(): Int {
        var result = fileId.hashCode()
        result = 31 * result + chunkCount
        result = 31 * result + chunksReceived
        result = 31 * result + (chunkBitmap?.contentHashCode() ?: 0)
        return result
    }
}

@Dao
interface VaultBlobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VaultBlobEntity)

    @Query("SELECT * FROM vault_blobs WHERE fileId = :fileId")
    suspend fun get(fileId: String): VaultBlobEntity?

    @Query("SELECT * FROM vault_blobs")
    suspend fun getAll(): List<VaultBlobEntity>

    @Query("""
        UPDATE vault_blobs
        SET chunkBitmap = :bitmap, chunksReceived = :chunksReceived, updatedAt = :updatedAt
        WHERE fileId = :fileId
    """)
    suspend fun updateChunkState(
        fileId: String,
        bitmap: ByteArray?,
        chunksReceived: Int,
        updatedAt: Long
    )

    @Query("DELETE FROM vault_blobs WHERE fileId = :fileId")
    suspend fun delete(fileId: String)

    @Query("SELECT COUNT(*) FROM vault_blobs")
    suspend fun count(): Int
}
