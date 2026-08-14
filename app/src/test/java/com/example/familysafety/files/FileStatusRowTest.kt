package com.example.familysafety.files

import com.example.familysafety.storage.SharedFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status rules are shared by the grid tile and the status board, so a mistake here shows
 * the user two different answers for the same file. They also decide the one line that
 * justifies the whole feature — whether an emergency document exists anywhere but this phone.
 */
class FileStatusRowTest {

    private fun entity(
        state: String = "PENDING",
        chunkCount: Int = 10,
        chunksReceived: Int = 0,
        attemptCount: Int = 0,
        isEssential: Boolean = true,
        lastError: String? = null
    ) = SharedFileEntity(
        fileId = "f1",
        name = "insurance-card.pdf",
        mimeType = "application/pdf",
        sizeBytes = 320_000,
        contentHash = "a".repeat(64),
        uploaderMemberId = "m1",
        uploadedAt = 1_786_000_000_000L,
        chunkCount = chunkCount,
        chunksReceived = chunksReceived,
        downloadState = state,
        attemptCount = attemptCount,
        isEssential = isEssential,
        lastError = lastError
    )

    private fun row(
        entity: SharedFileEntity = entity(),
        copiesElsewhere: Int = 0,
        totalMembers: Int = 4
    ) = FileStatusRow(
        entity = entity,
        uploaderName = "Maria",
        copiesElsewhere = copiesElsewhere,
        totalMembers = totalMembers,
        lastActivityAt = null,
        lastActivityDetail = null
    )

    @Test
    fun aCompleteFileIsAvailable() {
        assertEquals(FileStatus.AVAILABLE, row(entity(state = "COMPLETE")).status)
    }

    @Test
    fun aPartlyReceivedFileIsDownloading() {
        val r = row(entity(chunksReceived = 3))
        assertEquals(FileStatus.DOWNLOADING, r.status)
        assertEquals("3 of 10", r.shortLabel)
        assertEquals(0.3f, r.progressFraction, 0.001f)
    }

    @Test
    fun aRequestedFileIsWaitingRatherThanDownloading() {
        // The distinction the old UI could not draw: chunks have arrived, a request is out,
        // and nothing is moving. "Downloading" would imply progress that is not happening.
        assertEquals(FileStatus.WAITING_FOR_PEER, row(entity(chunksReceived = 3, attemptCount = 1)).status)
    }

    @Test
    fun repeatedFailedAttemptsReadAsStuck() {
        val r = row(entity(chunksReceived = 3, attemptCount = FileStatusRow.STALLED_AFTER_ATTEMPTS))
        assertEquals(FileStatus.STALLED, r.status)
        assertEquals("Stuck", r.shortLabel)
    }

    @Test
    fun anUntouchedNonEssentialFileIsOnDemandNotBroken() {
        // This is the case that would otherwise look like a permanently failing download.
        val r = row(entity(isEssential = false))
        assertEquals(FileStatus.ON_DEMAND, r.status)
        assertTrue(r.explanation.contains("Open it to download"))
    }

    @Test
    fun aNonEssentialFileBeingFetchedIsNoLongerOnDemand() {
        val r = row(entity(isEssential = false, chunksReceived = 2))
        assertEquals(FileStatus.DOWNLOADING, r.status)
    }

    @Test
    fun copiesCountThisDeviceOnlyWhenItActuallyHoldsTheFile() {
        val complete = row(entity(state = "COMPLETE"), copiesElsewhere = 2)
        assertEquals(3, complete.totalCopies)

        val notHere = row(entity(state = "PENDING"), copiesElsewhere = 2)
        assertEquals(2, notHere.totalCopies)
    }

    @Test
    fun aFileOnOnlyThisPhoneIsNotRedundantAndSaysSo() {
        // The warning the status board leads with. A document held once is one lost phone
        // away from gone, and nothing in the app used to say that.
        val r = row(entity(state = "COMPLETE"), copiesElsewhere = 0)

        assertFalse(r.isRedundant)
        assertEquals(1, r.totalCopies)
        assertTrue(r.explanation.contains("no other device"))
    }

    @Test
    fun aReplicatedFileReportsHowManyOthersHoldIt() {
        val r = row(entity(state = "COMPLETE"), copiesElsewhere = 3)

        assertTrue(r.isRedundant)
        assertEquals("On this phone and 3 others", r.explanation)
    }

    @Test
    fun oneOtherCopyIsSingular() {
        assertEquals("On this phone and 1 other", row(entity(state = "COMPLETE"), copiesElsewhere = 1).explanation)
    }

    @Test
    fun aStalledFileSurfacesTheRecordedReason() {
        val r = row(entity(state = "FAILED", lastError = "could not reach 9d3ece3b"))
        assertEquals(FileStatus.STALLED, r.status)
        assertEquals("could not reach 9d3ece3b", r.explanation)
    }

    @Test
    fun everyStatusProducesANonEmptyLabelAndExplanation() {
        // The board renders both for every row; a blank cell is a bug the user sees.
        val samples = listOf(
            entity(state = "COMPLETE"),
            entity(state = "FAILED"),
            entity(isEssential = false),
            entity(chunksReceived = 1),
            entity(chunksReceived = 1, attemptCount = 1),
            entity()
        )
        samples.forEach { e ->
            val r = row(e)
            assertTrue("label for ${r.status}", r.shortLabel.isNotBlank())
            assertTrue("explanation for ${r.status}", r.explanation.isNotBlank())
        }
    }

    @Test
    fun progressIsZeroRatherThanDividingByZeroBeforeMetadataArrives() {
        assertEquals(0f, row(entity(chunkCount = 0)).progressFraction, 0.0001f)
    }
}
