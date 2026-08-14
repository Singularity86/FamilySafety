package com.example.familysafety.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chunk accounting used to be `tmpDir.listFiles()?.size` — how many chunks had arrived, never
 * which ones. Everything that makes a transfer self-healing depends on these bits being right,
 * so the boundaries are pinned deliberately: a wrong answer here means either a file reported
 * complete with a hole in it, or one that can never complete.
 */
class ChunkBitmapTest {

    @Test
    fun sizeRoundsUpToWholeBytes() {
        assertEquals(0, ChunkBitmap.sizeFor(0))
        assertEquals(1, ChunkBitmap.sizeFor(1))
        assertEquals(1, ChunkBitmap.sizeFor(8))
        assertEquals(2, ChunkBitmap.sizeFor(9))
        assertEquals(400, ChunkBitmap.sizeFor(3200))
    }

    @Test
    fun aBitmapStartsEmpty() {
        val bitmap = ChunkBitmap.empty(20)
        assertEquals(0, ChunkBitmap.count(bitmap, 20))
        assertFalse(ChunkBitmap.isComplete(bitmap, 20))
        assertEquals((0 until 20).toList(), ChunkBitmap.missingIndices(bitmap, 20))
    }

    @Test
    fun settingABitIsVisibleAndIsolated() {
        val bitmap = ChunkBitmap.withBitSet(ChunkBitmap.empty(16), 5, 16)

        assertTrue(ChunkBitmap.isSet(bitmap, 5))
        (0 until 16).filter { it != 5 }.forEach { assertFalse(ChunkBitmap.isSet(bitmap, it)) }
        assertEquals(1, ChunkBitmap.count(bitmap, 16))
    }

    @Test
    fun settingTheSameBitTwiceCountsOnce() {
        // At-least-once delivery means the same chunk arrives repeatedly; a duplicate must not
        // inflate progress, or a file with a gap reports itself complete.
        var bitmap = ChunkBitmap.withBitSet(ChunkBitmap.empty(10), 3, 10)
        bitmap = ChunkBitmap.withBitSet(bitmap, 3, 10)
        bitmap = ChunkBitmap.withBitSet(bitmap, 3, 10)

        assertEquals(1, ChunkBitmap.count(bitmap, 10))
    }

    @Test
    fun withBitSetDoesNotMutateTheInput() {
        // Room may still hold the array the caller passed in.
        val original = ChunkBitmap.empty(8)
        ChunkBitmap.withBitSet(original, 2, 8)

        assertEquals(0, ChunkBitmap.count(original, 8))
    }

    @Test
    fun crossesByteBoundariesCorrectly() {
        var bitmap = ChunkBitmap.empty(24)
        listOf(0, 7, 8, 15, 16, 23).forEach { bitmap = ChunkBitmap.withBitSet(bitmap, it, 24) }

        listOf(0, 7, 8, 15, 16, 23).forEach { assertTrue("bit $it", ChunkBitmap.isSet(bitmap, it)) }
        listOf(1, 6, 9, 14, 17, 22).forEach { assertFalse("bit $it", ChunkBitmap.isSet(bitmap, it)) }
        assertEquals(6, ChunkBitmap.count(bitmap, 24))
    }

    @Test
    fun completeOnlyWhenEveryChunkIsHeld() {
        var bitmap = ChunkBitmap.empty(5)
        (0 until 4).forEach { bitmap = ChunkBitmap.withBitSet(bitmap, it, 5) }

        assertFalse("one short is not complete", ChunkBitmap.isComplete(bitmap, 5))
        assertEquals(listOf(4), ChunkBitmap.missingIndices(bitmap, 5))

        bitmap = ChunkBitmap.withBitSet(bitmap, 4, 5)
        assertTrue(ChunkBitmap.isComplete(bitmap, 5))
        assertEquals(emptyList<Int>(), ChunkBitmap.missingIndices(bitmap, 5))
    }

    @Test
    fun spareBitsInTheLastByteDoNotCountAsChunks() {
        // 12 chunks occupy 2 bytes; bits 12..15 exist but are not chunks. If they were counted
        // a file would report complete before its last chunks arrived.
        val full = ByteArray(2) { 0xFF.toByte() }

        assertEquals(12, ChunkBitmap.count(full, 12))
        assertTrue(ChunkBitmap.isComplete(full, 12))
    }

    @Test
    fun missingIndicesRespectsTheCap() {
        val bitmap = ChunkBitmap.empty(1000)
        val missing = ChunkBitmap.missingIndices(bitmap, 1000, limit = 256)

        assertEquals(256, missing.size)
        assertEquals(0, missing.first())
        assertEquals(255, missing.last())
    }

    @Test
    fun missingIndicesAreAscendingAndSkipHeldChunks() {
        var bitmap = ChunkBitmap.empty(10)
        listOf(0, 1, 4, 9).forEach { bitmap = ChunkBitmap.withBitSet(bitmap, it, 10) }

        assertEquals(listOf(2, 3, 5, 6, 7, 8), ChunkBitmap.missingIndices(bitmap, 10))
    }

    @Test
    fun aNullBitmapReadsAsNothingHeld() {
        // Rows written before this column existed. They must look empty, not complete.
        assertFalse(ChunkBitmap.isSet(null, 0))
        assertEquals(0, ChunkBitmap.count(null, 10))
        assertFalse(ChunkBitmap.isComplete(null, 10))
        assertEquals((0 until 10).toList(), ChunkBitmap.missingIndices(null, 10))
    }

    @Test
    fun aShortBitmapGrowsInsteadOfThrowing() {
        // chunkCount can grow relative to a stored bitmap if a row is rewritten from a
        // manifest. Treat the absent tail as unset rather than indexing off the end.
        val short = ChunkBitmap.empty(8)

        assertFalse(ChunkBitmap.isSet(short, 20))
        assertEquals(0, ChunkBitmap.count(short, 24))

        val grown = ChunkBitmap.withBitSet(short, 20, 24)
        assertTrue(ChunkBitmap.isSet(grown, 20))
        assertEquals(3, grown.size)
    }

    @Test
    fun outOfRangeIndicesAreIgnoredRatherThanCorrupting() {
        val bitmap = ChunkBitmap.withBitSet(ChunkBitmap.empty(8), 8, 8)
        assertEquals(0, ChunkBitmap.count(bitmap, 8))

        val negative = ChunkBitmap.withBitSet(ChunkBitmap.empty(8), -1, 8)
        assertEquals(0, ChunkBitmap.count(negative, 8))
    }

    @Test
    fun aZeroChunkFileIsNeverComplete() {
        // chunkCount 0 means the metadata has not arrived yet; treating it as complete would
        // mark an unknown file finished.
        assertFalse(ChunkBitmap.isComplete(ChunkBitmap.empty(0), 0))
        assertEquals(emptyList<Int>(), ChunkBitmap.missingIndices(null, 0))
    }

    @Test
    fun aRealisticFileTracksEveryChunk() {
        // 100 MB at 32 KB chunks — 3200 chunks in 400 bytes, which is the argument for a
        // bitmap over a row per chunk.
        val chunkCount = 3200
        var bitmap = ChunkBitmap.empty(chunkCount)
        assertEquals(400, bitmap.size)

        (0 until chunkCount).forEach { bitmap = ChunkBitmap.withBitSet(bitmap, it, chunkCount) }
        assertTrue(ChunkBitmap.isComplete(bitmap, chunkCount))

        val holed = ChunkBitmap.empty(chunkCount).let { empty ->
            var b = empty
            (0 until chunkCount).filter { it != 1723 }.forEach { b = ChunkBitmap.withBitSet(b, it, chunkCount) }
            b
        }
        assertFalse(ChunkBitmap.isComplete(holed, chunkCount))
        assertEquals(listOf(1723), ChunkBitmap.missingIndices(holed, chunkCount))
    }
}
