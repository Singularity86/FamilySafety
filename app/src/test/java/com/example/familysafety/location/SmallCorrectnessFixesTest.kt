package com.example.familysafety.location

import android.content.Intent
import com.example.familysafety.avatar.AvatarRepository
import com.example.familysafety.storage.LocationHistoryRepository
import com.example.familysafety.storage.LocationOutboxPolicy
import com.example.familysafety.storage.PendingLocationPublishEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Remediation 1.8: the small fixes that can be pinned without a device. */
class SmallCorrectnessFixesTest {

    private fun pending(id: Long, timestamp: Long) = PendingLocationPublishEntity(
        id = id, memberId = "alice", latitude = 0.0, longitude = 0.0, accuracy = 5f, timestamp = timestamp
    )

    @Test
    fun `after an outage only the newest queued fix is published`() {
        val (newest, older) = LocationOutboxPolicy.split(listOf(pending(1, 100), pending(2, 300), pending(3, 200)))
        assertEquals(2L, newest?.id)
        assertEquals(setOf(1L, 3L), older.map { it.id }.toSet())
    }

    @Test
    fun `an empty outbox publishes nothing`() {
        val (newest, older) = LocationOutboxPolicy.split(emptyList())
        assertNull(newest)
        assertTrue(older.isEmpty())
    }

    @Test
    fun `today starts at local midnight, not UTC midnight`() {
        val newYork = ZoneId.of("America/New_York")
        // 9 pm in New York is already the next day in UTC.
        val ninePm = LocalDateTime.of(2026, 10, 11, 21, 0).atZone(newYork).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 10, 11, 0, 0).atZone(newYork).toInstant().toEpochMilli()
        assertEquals(expected, LocationHistoryRepository.startOfLocalDay(ninePm, newYork))
    }

    @Test
    fun `an avatar response over the cap is refused, not read into memory`() {
        val small = ByteArray(1000) { 7 }
        assertArrayEquals(small, AvatarRepository.readCapped(small.inputStream(), 4096))
        assertNull(AvatarRepository.readCapped(ByteArray(5000).inputStream(), 4096))
    }

    @Test
    fun `the boot receiver acts only on boot and update broadcasts`() {
        assertTrue(Intent.ACTION_BOOT_COMPLETED in BootRecoveryReceiver.HANDLED_ACTIONS)
        assertTrue(Intent.ACTION_MY_PACKAGE_REPLACED in BootRecoveryReceiver.HANDLED_ACTIONS)
        assertFalse("com.example.SOMETHING_ELSE" in BootRecoveryReceiver.HANDLED_ACTIONS)
        assertFalse(Intent.ACTION_MAIN in BootRecoveryReceiver.HANDLED_ACTIONS)
    }
}
