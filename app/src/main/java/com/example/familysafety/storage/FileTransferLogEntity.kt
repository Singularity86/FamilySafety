package com.example.familysafety.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only record of what happened to a file transfer.
 *
 * The reason this exists: a file once took half a day to arrive, and afterwards there was no
 * way to tell whether it had been retrying the whole time, blocked on a peer, or simply
 * stopped. Nothing recorded the difference. Every question worth asking about a stalled
 * transfer — when did it last try, who did it ask, what came back — was unanswerable.
 *
 * Deliberately small and bounded: events only, no payloads, trimmed by age. It is a
 * diagnostic, not an audit trail, and it lives in the SQLCipher database like everything else.
 */
@Entity(
    tableName = "file_transfer_log",
    indices = [Index("fileId"), Index("at")]
)
data class FileTransferLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileId: String,
    val at: Long,
    /** Short machine-readable event key; see [FileTransferEvent]. */
    val event: String,
    /** Human-readable detail, safe to show the user. Never contains file contents. */
    val detail: String? = null
)

/**
 * Event keys. Kept as constants rather than an enum so a row written by a newer build is
 * still readable by an older one rather than failing to deserialize.
 */
object FileTransferEvent {
    const val ADDED = "added"
    const val UPLOAD_STARTED = "upload_started"
    const val UPLOAD_FINISHED = "upload_finished"
    const val CHUNKS_REQUESTED = "chunks_requested"
    const val CHUNKS_SERVED = "chunks_served"
    const val DOWNLOAD_COMPLETE = "download_complete"
    const val HASH_MISMATCH = "hash_mismatch"
    const val FAILED = "failed"
    const val DELETED = "deleted"
    const val ESSENTIAL_CHANGED = "essential_changed"
}
