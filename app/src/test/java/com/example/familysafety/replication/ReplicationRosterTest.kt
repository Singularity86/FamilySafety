package com.example.familysafety.replication

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.storage.DataSummary
import com.example.familysafety.storage.LocationHistoryRepository
import com.example.familysafety.transport.TransportProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Location history moves only for members of this family. A phone that changed families still
 * held the old family's history, announced it, and served it page after page to a member of the
 * new one, who could only drop it (2026-10-27 two-phone test).
 */
class ReplicationRosterTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun member(id: String) = FamilyMember(
        memberId = id, displayName = id, ed25519PublicKey = "a".repeat(64),
        x25519PublicKey = "b".repeat(64), addedAtEpochMs = 1L
    )
    private val family = GroupDefinition(
        groupId = "g", groupName = "S9Fam", createdAtEpochMs = 1L, creatorMemberId = "me",
        members = setOf(member("me"), member("peer")), version = 1L
    )
    private val oldFamilyMember = "8cc7a2aa"

    private val history = mockk<LocationHistoryRepository>(relaxed = true)
    private val e2ee = mockk<E2EEManager>(relaxed = true)
    private val transport = mockk<TransportProvider>(relaxed = true)
    private lateinit var manager: ReplicationManager

    @Before
    fun setUp() {
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.groupDefinition } returns MutableStateFlow(family)
        every { gsm.localMember } returns MutableStateFlow(member("me"))
        coEvery { transport.sendMessage(any(), any(), any(), any(), any()) } returns true
        coEvery { history.getLocationsAfter(any(), any()) } answers {
            listOf(MemberLocation(memberId = firstArg(), latitude = 1.0, longitude = 1.0, accuracy = 5f, timestamp = 10L))
        }
        manager = ReplicationManager(gsm, mockk(relaxed = true), history, mockk(relaxed = true), e2ee, transport)
    }

    /** The decrypted body of the next incoming message. */
    private fun incoming(body: String) {
        every { e2ee.decryptMessage(any(), any(), any()) } returns body
    }

    private fun locationRequest(target: String) = json.encodeToString(
        ReplicationRequest(
            requestId = "r1", requesterId = "peer", dataType = ReplicationDataType.LOCATION_HISTORY,
            targetMemberId = target, afterTimestamp = 0L, limit = 500
        )
    )

    @Test
    fun `history of someone outside the family is not served`() = runTest {
        incoming(locationRequest(oldFamilyMember))
        manager.handleReplicationRequest("{}", "peer")

        coVerify(exactly = 0) { transport.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a member's history still is`() = runTest {
        incoming(locationRequest("me"))
        manager.handleReplicationRequest("{}", "peer")

        coVerify(exactly = 1) { transport.sendMessage("peer", any(), any(), any(), any()) }
    }

    @Test
    fun `an announcement of a non-member's history prompts no request`() = runTest {
        incoming(
            json.encodeToString(
                DataAvailabilityAnnouncement(
                    announcerId = "peer",
                    locationDataSummary = listOf(MemberDataSummary(oldFamilyMember, 1L, 100L, 500)),
                    chatDataSummary = emptyList()
                )
            )
        )
        manager.handleDataAvailabilityAnnouncement("{}", "peer")

        coVerify(exactly = 0) { transport.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `our announcement lists only members' history`() = runTest {
        coEvery { history.getDataSummary() } returns mapOf(
            "me" to DataSummary(1L, 100L, 10),
            oldFamilyMember to DataSummary(1L, 100L, 500)
        )
        val sent = slot<String>()
        every { e2ee.encryptMessage(capture(sent), any(), any(), any()) } returns "{}"

        manager.announceDataAvailability()

        val announced = json.decodeFromString<DataAvailabilityAnnouncement>(sent.captured)
        assertEquals(listOf("me"), announced.locationDataSummary.map { it.memberId })
    }
}
