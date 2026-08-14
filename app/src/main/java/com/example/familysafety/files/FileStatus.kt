package com.example.familysafety.files

import com.example.familysafety.storage.SharedFileEntity

/**
 * What a file's state actually means, in one place.
 *
 * The stored `downloadState` is not enough on its own to tell a user anything useful. "PENDING"
 * covers both "waiting its turn" and "nobody has answered for an hour", and those deserve
 * different words. This folds in the retry schedule, the chunk accounting and how many peers
 * hold a copy, so the UI never has to re-derive it and the status board and the grid tile
 * cannot disagree.
 */
enum class FileStatus {
    /** Held in full and hash-verified. */
    AVAILABLE,

    /** This device is the source. */
    UPLOADING,

    /** Chunks are arriving. */
    DOWNLOADING,

    /** Missing chunks, a request is out, waiting on a peer to answer. */
    WAITING_FOR_PEER,

    /** Listed but not fetched, because it is not marked essential. Tap to download. */
    ON_DEMAND,

    /** Tried repeatedly and got nowhere. */
    STALLED,

    /** Known, not started yet. */
    QUEUED
}

/**
 * A file plus everything needed to describe it to a user, computed once.
 *
 * @param copiesElsewhere peers that have announced a complete, hash-matching copy.
 * @param totalMembers family size, so "3 of 4" can be stated rather than a bare count.
 */
data class FileStatusRow(
    val entity: SharedFileEntity,
    val uploaderName: String,
    val copiesElsewhere: Int,
    val totalMembers: Int,
    val lastActivityAt: Long?,
    val lastActivityDetail: String?
) {
    val status: FileStatus = when {
        entity.downloadState == "COMPLETE" -> FileStatus.AVAILABLE
        entity.downloadState == "FAILED" -> FileStatus.STALLED
        !entity.isEssential && entity.chunksReceived == 0 -> FileStatus.ON_DEMAND
        entity.attemptCount >= STALLED_AFTER_ATTEMPTS -> FileStatus.STALLED
        entity.chunksReceived > 0 && entity.attemptCount > 0 -> FileStatus.WAITING_FOR_PEER
        entity.chunksReceived > 0 -> FileStatus.DOWNLOADING
        entity.attemptCount > 0 -> FileStatus.WAITING_FOR_PEER
        else -> FileStatus.QUEUED
    }

    /**
     * Copies across the whole family, including this device.
     *
     * This is the number that answers "is this document safe?", which for an emergency
     * document is the only question that matters and the one the app could not previously
     * answer at all.
     */
    val totalCopies: Int =
        copiesElsewhere + if (entity.downloadState == "COMPLETE") 1 else 0

    /** True when at least one other device holds it, so losing this phone is survivable. */
    val isRedundant: Boolean = copiesElsewhere > 0

    val progressFraction: Float =
        if (entity.chunkCount > 0) entity.chunksReceived.toFloat() / entity.chunkCount else 0f

    /** Short label for the tile chip. */
    val shortLabel: String = when (status) {
        FileStatus.AVAILABLE -> "Available"
        FileStatus.UPLOADING -> "Sending"
        FileStatus.DOWNLOADING -> "${entity.chunksReceived} of ${entity.chunkCount}"
        FileStatus.WAITING_FOR_PEER -> "Waiting"
        FileStatus.ON_DEMAND -> "Tap to get"
        FileStatus.STALLED -> "Stuck"
        FileStatus.QUEUED -> "Queued"
    }

    /**
     * A sentence for the status board.
     *
     * Deliberately says what is happening and what the user can expect, rather than naming an
     * internal state. "Waiting for a family member's phone" is actionable; "PENDING" is not.
     */
    val explanation: String = when (status) {
        FileStatus.AVAILABLE ->
            if (isRedundant) "On this phone and $copiesElsewhere other${if (copiesElsewhere == 1) "" else "s"}"
            else "On this phone only — no other device has a copy yet"
        FileStatus.UPLOADING -> "Sending to the family"
        FileStatus.DOWNLOADING -> "Downloading — ${entity.chunksReceived} of ${entity.chunkCount} pieces"
        FileStatus.WAITING_FOR_PEER ->
            "Waiting for a family member's phone to send the rest" +
                (entity.lastError?.let { " ($it)" } ?: "")
        FileStatus.ON_DEMAND -> "Not downloaded to this phone. Open it to download."
        FileStatus.STALLED ->
            entity.lastError ?: "Tried ${entity.attemptCount} times without success"
        FileStatus.QUEUED -> "Waiting to start"
    }

    companion object {
        /**
         * Attempts before a file is called stuck rather than merely waiting.
         *
         * Backoff reaches its 30-minute ceiling around here, so by this point the app really
         * has been trying for a long while and the user deserves to be told plainly.
         */
        const val STALLED_AFTER_ATTEMPTS = 6
    }
}
