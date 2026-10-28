package com.example.familysafety.files

import com.example.familysafety.storage.SharedFileEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a peer's holdings show it never heard of a file we shared, the manifest goes out again
 * (two-phone test, 2026-10-27: a photo shared as Android Auto took the Wi-Fi never arrived).
 */
class ManifestResendTest {

    private val me = "me"
    private val now = 1_800_000_000_000L
    private val old = now - SharedFileRepository.MANIFEST_RESEND_MIN_AGE_MS

    private fun file(
        id: String,
        uploader: String = me,
        state: String = "COMPLETE",
        uploadedAt: Long = old,
        deleted: Boolean = false
    ) = SharedFileEntity(
        fileId = id, name = "$id.jpg", mimeType = "image/jpeg", sizeBytes = 1000,
        contentHash = "a".repeat(64), uploaderMemberId = uploader, uploadedAt = uploadedAt,
        chunkCount = 1, chunksReceived = if (state == "COMPLETE") 1 else 0, downloadState = state,
        isDeleted = deleted
    )

    private fun unknown(files: List<SharedFileEntity>, announced: Set<String>) =
        SharedFileRepository.sharedButUnknownTo(files, announced, me, now).map { it.fileId }

    @Test
    fun `our shared file missing from a peer's holdings is resent`() {
        assertEquals(listOf("photo"), unknown(listOf(file("photo")), emptySet()))
    }

    @Test
    fun `a file the peer lists, even partly held, is not`() {
        assertEquals(emptyList<String>(), unknown(listOf(file("photo")), setOf("photo")))
    }

    @Test
    fun `someone else's file is theirs to resend`() {
        assertEquals(emptyList<String>(), unknown(listOf(file("theirs", uploader = "bob")), emptySet()))
    }

    @Test
    fun `a share from the last two minutes may just be newer than the announcement`() {
        assertEquals(emptyList<String>(), unknown(listOf(file("fresh", uploadedAt = old + 1)), emptySet()))
    }

    @Test
    fun `incomplete and deleted files are not resent`() {
        val files = listOf(file("partial", state = "PENDING"), file("gone", deleted = true))
        assertEquals(emptyList<String>(), unknown(files, emptySet()))
    }
}
