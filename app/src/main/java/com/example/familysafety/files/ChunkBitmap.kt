package com.example.familysafety.files

/**
 * Which chunks of a file we hold, as a bit per chunk.
 *
 * Download progress used to be `tmpDir.listFiles()?.size` compared against `chunkCount` — a
 * count of files present, not of *which indices* are present. That cannot answer the only
 * question that matters when a transfer stalls: which chunks are missing. Without it there is
 * no gap detection, so a single lost chunk left a file stuck forever and the sole recovery was
 * a peer re-broadcasting its entire library.
 *
 * A bitmap rather than a table: a 100 MB file at 32 KB chunks is 3200 chunks = 400 bytes, and
 * the whole group's accounting at the 500 MB cap is a couple of kilobytes. The equivalent
 * table would be tens of thousands of rows, and gap detection would be a `NOT IN` subquery per
 * file instead of a local scan. Room maps `ByteArray` to BLOB natively, so it rides along on
 * the row that already holds `chunkCount`.
 *
 * Bit *i* is chunk *i*, LSB-first within each byte. All functions tolerate a null or short
 * bitmap by treating the missing bits as unset, so a row written before this existed reads as
 * "nothing held" rather than throwing.
 */
object ChunkBitmap {

    /** Bytes needed to track [chunkCount] chunks. */
    fun sizeFor(chunkCount: Int): Int = if (chunkCount <= 0) 0 else (chunkCount + 7) / 8

    fun empty(chunkCount: Int): ByteArray = ByteArray(sizeFor(chunkCount))

    fun isSet(bitmap: ByteArray?, index: Int): Boolean {
        if (bitmap == null || index < 0) return false
        val byte = index / 8
        if (byte >= bitmap.size) return false
        return (bitmap[byte].toInt() shr (index % 8)) and 1 == 1
    }

    /**
     * Returns a copy with bit [index] set, growing to [chunkCount] if the stored bitmap is
     * absent or too short. Copying keeps callers from mutating a `ByteArray` that Room may
     * still be holding.
     */
    fun withBitSet(bitmap: ByteArray?, index: Int, chunkCount: Int): ByteArray {
        val required = sizeFor(chunkCount)
        val out = when {
            bitmap == null -> ByteArray(required)
            bitmap.size < required -> bitmap.copyOf(required)
            else -> bitmap.copyOf()
        }
        if (index < 0 || index >= chunkCount) return out
        val byte = index / 8
        out[byte] = (out[byte].toInt() or (1 shl (index % 8))).toByte()
        return out
    }

    /** How many of the first [chunkCount] bits are set. */
    fun count(bitmap: ByteArray?, chunkCount: Int): Int {
        if (bitmap == null || chunkCount <= 0) return 0
        var total = 0
        for (i in 0 until chunkCount) if (isSet(bitmap, i)) total++
        return total
    }

    fun isComplete(bitmap: ByteArray?, chunkCount: Int): Boolean =
        chunkCount > 0 && count(bitmap, chunkCount) == chunkCount

    /**
     * Missing chunk indices, ascending, capped at [limit].
     *
     * The cap exists because this feeds a repair request that has to fit in one message —
     * asking for 3200 indices at once would be both a large payload and more than any peer
     * should send in one go. The requester re-asks until the file completes.
     */
    fun missingIndices(bitmap: ByteArray?, chunkCount: Int, limit: Int = Int.MAX_VALUE): List<Int> {
        if (chunkCount <= 0 || limit <= 0) return emptyList()
        val missing = ArrayList<Int>()
        for (i in 0 until chunkCount) {
            if (!isSet(bitmap, i)) {
                missing.add(i)
                if (missing.size >= limit) break
            }
        }
        return missing
    }
}
