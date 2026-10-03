package com.example.familysafety.chat

import app.cash.turbine.test
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.storage.ChatMessageDao
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Chat badge must follow the group as it loads. Built before the group was in memory,
 * it used to query with an empty member ID — which matches every row — and showed a count
 * of messages no screen could display.
 */
class ChatUnreadBadgeTest {

    private val me = FamilyMember(
        memberId = "local_001",
        displayName = "Me",
        ed25519PublicKey = "a".repeat(64),
        x25519PublicKey = "b".repeat(64),
        addedAtEpochMs = 1000L
    )
    private val group = GroupDefinition(
        groupId = "group-1",
        groupName = "Family",
        createdAtEpochMs = 1000L,
        creatorMemberId = me.memberId,
        members = setOf(me),
        version = 1
    )

    @Test
    fun `badge waits for the group instead of counting every row`() = runTest {
        val dao = mockk<ChatMessageDao>(relaxed = true)
        every { dao.observeTotalUnreadCountFor(me.memberId, group.groupId) } returns flowOf(2)

        val localMember = MutableStateFlow<FamilyMember?>(null)
        val groupDefinition = MutableStateFlow<GroupDefinition?>(null)
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.localMember } returns localMember
        every { gsm.groupDefinition } returns groupDefinition

        val repository = ChatRepository(dao, gsm, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true))

        repository.observeTotalUnreadCount().test {
            assertEquals(0, awaitItem())
            groupDefinition.value = group
            localMember.value = me
            assertEquals(2, expectMostRecentItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { dao.observeTotalUnreadCountFor("", any()) }
    }
}
