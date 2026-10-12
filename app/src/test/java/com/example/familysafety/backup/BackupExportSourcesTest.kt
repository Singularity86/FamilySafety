package com.example.familysafety.backup

import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.storage.StorageUnavailableException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each way export can fail gets its own message, not one catch-all. */
class BackupExportSourcesTest {

    private val words = List(12) { "word$it" }
    private val family = GroupDefinition(
        groupId = "g1",
        groupName = "Test Family",
        createdAtEpochMs = 1000L,
        creatorMemberId = "m1",
        members = emptySet(),
        version = 1L
    )

    private fun missing(result: BackupExportSources.Result) =
        (result as BackupExportSources.Result.Missing).message

    @Test
    fun `phrase and family present is ready`() = runTest {
        val result = BackupExportSources.gather({ words }, { family })
        assertTrue(result is BackupExportSources.Result.Ready)
    }

    @Test
    fun `no phrase saved says a backup isn't possible`() = runTest {
        assertEquals(BackupExportSources.NO_PHRASE_SAVED, missing(BackupExportSources.gather({ null }, { family })))
        assertEquals(BackupExportSources.NO_PHRASE_SAVED, missing(BackupExportSources.gather({ emptyList() }, { family })))
    }

    @Test
    fun `unreadable phrase says try again, not missing`() = runTest {
        val result = BackupExportSources.gather({ throw StorageUnavailableException("Identity") }, { family })
        assertEquals(BackupExportSources.PHRASE_UNREADABLE, missing(result))
    }

    @Test
    fun `unreadable family says try again`() = runTest {
        val result = BackupExportSources.gather({ words }, { throw StorageUnavailableException("Family") })
        assertEquals(BackupExportSources.FAMILY_UNREADABLE, missing(result))
    }

    @Test
    fun `no family says there's nothing to back up`() = runTest {
        assertEquals(BackupExportSources.NO_FAMILY, missing(BackupExportSources.gather({ words }, { null })))
    }

    @Test
    fun `a leftover export is swept once it has had time to be shared`() {
        // F33: the file holds the recovery phrase under the backup password.
        val now = 1_000_000_000L
        assertTrue(BackupManager.isStale(true, now - BackupManager.EXPORT_FILE_LIFETIME_MS, now))
        assertTrue(!BackupManager.isStale(true, now - 60_000, now))
        assertTrue(!BackupManager.isStale(false, 0, now))
    }

    @Test
    fun `file failure names the reason`() {
        assertTrue(BackupExportSources.fileNotSaved("No space left on device").contains("No space left on device"))
    }
}
