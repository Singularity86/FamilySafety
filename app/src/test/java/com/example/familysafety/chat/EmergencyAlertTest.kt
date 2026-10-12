package com.example.familysafety.chat

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.storage.ChatMessageDao
import com.example.familysafety.storage.ChatMessageEntity
import com.example.familysafety.storage.EmergencyOutboxDao
import com.example.familysafety.storage.EmergencyOutboxEntity
import com.example.familysafety.storage.EmergencyRetrySchedule
import com.example.familysafety.storage.MessageStatus
import com.example.familysafety.storage.MessageType
import com.example.familysafety.transport.TransportProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F27: a crash alert is stored per family member and re-sent until that member's phone
 * confirms it, instead of riding best-effort chat that nothing retried.
 */
class EmergencyAlertTest {

    private lateinit var outbox: FakeEmergencyOutboxDao
    private lateinit var chatDao: ChatMessageDao
    private lateinit var transport: TransportProvider
    private lateinit var e2ee: E2EEManager
    private lateinit var repo: ChatRepository

    private val json = Json { ignoreUnknownKeys = true }
    private fun member(id: String) = FamilyMember(
        memberId = id, displayName = id, ed25519PublicKey = "a".repeat(64),
        x25519PublicKey = "b".repeat(64), addedAtEpochMs = 1000L
    )
    private val family = GroupDefinition(
        groupId = "group_123", groupName = "Test Family", createdAtEpochMs = 1000L,
        creatorMemberId = "alice", members = listOf("alice", "bob", "carol", "dave").map { member(it) }.toSet(),
        version = 1L
    )

    @Before
    fun setup() {
        outbox = FakeEmergencyOutboxDao()
        chatDao = mockk(relaxed = true)
        transport = mockk(relaxed = true)
        e2ee = mockk(relaxed = true)
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.localMember } returns MutableStateFlow(member("alice"))
        every { gsm.groupDefinition } returns MutableStateFlow(family)
        every { e2ee.encryptMessage(any(), any(), any()) } returns "{}"
        coEvery { transport.sendMessage(any(), any(), any(), any(), any()) } returns true
        repo = ChatRepository(chatDao, gsm, e2ee, mockk(relaxed = true), transport, mockk(relaxed = true), outbox)
    }

    private fun row(recipient: String, attempts: Int = 0, nextAt: Long = 0, createdAt: Long = 1_000L) =
        EmergencyOutboxEntity(
            alertId = "alert_1", recipientId = recipient, groupId = "group_123",
            content = "help", timestamp = 1_000L, createdAt = createdAt,
            attemptCount = attempts, nextAttemptAt = nextAt
        )

    @Test
    fun `raising an alert stores one copy per other member and sends them`() {
        val alertId = repo.raiseEmergencyAlert("help")
        assertNotNull(alertId)

        awaitTrue { outbox.rows.size == 3 && outbox.rows.values.all { it.attemptCount == 1 } }
        assertEquals(setOf("bob", "carol", "dave"), outbox.rows.values.map { it.recipientId }.toSet())
        coVerify(exactly = 3) { transport.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a copy is re-sent when due, with the same alert id`() = runTest {
        outbox.insertAll(listOf(row("bob", attempts = 1, nextAt = 5_000L)))

        repo.flushEmergencyAlerts(now = 4_000L)
        coVerify(exactly = 0) { transport.sendMessage(any(), any(), any(), any(), any()) }

        repo.flushEmergencyAlerts(now = 5_000L)
        coVerify(exactly = 1) { transport.sendMessage("bob", any(), any(), any(), any()) }
        val after = outbox.rows.getValue("alert_1" to "bob")
        assertEquals(2, after.attemptCount)
        assertEquals(5_000L + EmergencyRetrySchedule.delayAfter(2), after.nextAttemptAt)
    }

    @Test
    fun `a sent copy is retried anyway until a receipt arrives`() = runTest {
        // A publish only means the broker took it; the member's phone may be off.
        outbox.insertAll(listOf(row("bob")))
        repo.flushEmergencyAlerts(now = 1_000L)
        assertNull(outbox.rows.getValue("alert_1" to "bob").deliveredAt)
        repo.flushEmergencyAlerts(now = 1_000L + EmergencyRetrySchedule.delayAfter(1))
        coVerify(exactly = 2) { transport.sendMessage("bob", any(), any(), any(), any()) }
    }

    @Test
    fun `the member's receipt stops the retries`() = runTest {
        outbox.insertAll(listOf(row("bob")))
        every { e2ee.decryptMessage(any(), any(), any()) } returns json.encodeToString(
            DeliveryReceipt(messageId = "alert_1", recipientId = "bob", status = MessageStatus.DELIVERED, timestamp = 2_000L)
        )

        repo.handleDeliveryReceipt("ciphertext", senderMemberId = "bob")
        assertNotNull(outbox.rows.getValue("alert_1" to "bob").deliveredAt)

        repo.flushEmergencyAlerts(now = Long.MAX_VALUE / 2)
        coVerify(exactly = 0) { transport.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `one member can't confirm for another`() = runTest {
        outbox.insertAll(listOf(row("bob")))
        every { e2ee.decryptMessage(any(), any(), any()) } returns json.encodeToString(
            DeliveryReceipt(messageId = "alert_1", recipientId = "bob", status = MessageStatus.DELIVERED, timestamp = 2_000L)
        )

        // Signed by carol but claiming to be bob's receipt.
        repo.handleDeliveryReceipt("ciphertext", senderMemberId = "carol")
        assertNull(outbox.rows.getValue("alert_1" to "bob").deliveredAt)
    }

    @Test
    fun `retries stop after a day`() = runTest {
        outbox.insertAll(listOf(row("bob", createdAt = 1_000L)))
        repo.flushEmergencyAlerts(now = 1_000L + EmergencyRetrySchedule.GIVE_UP_AFTER_MS + 1)
        coVerify(exactly = 0) { transport.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a duplicate message is confirmed again, so a lost receipt doesn't mean retrying forever`() = runTest {
        every { e2ee.decryptMessage(any(), any(), any()) } returns json.encodeToString(
            ChatMessagePayload(
                messageId = "alert_1", content = "help", messageType = MessageType.TEXT,
                timestamp = 1_000L, conversationId = "group_123"
            )
        )
        coEvery { chatDao.messageExists("alert_1") } returns true

        repo.handleIncomingMessage("ciphertext", senderMemberId = "bob")

        coVerify(exactly = 1) { transport.sendMessage("bob", any(), any(), any(), any()) }
    }

    @Test
    fun `a failed group message is retried to every member`() = runTest {
        coEvery { chatDao.getFailedMessages() } returns listOf(
            ChatMessageEntity(
                conversationId = "group_123", senderId = "alice", recipientId = null,
                content = "Dinner at 7", messageType = MessageType.TEXT,
                status = MessageStatus.FAILED, isOutgoing = true
            )
        )

        repo.retrySendingFailedMessages()

        coVerify(exactly = 3) { transport.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `retry schedule is 15s, 30s, then every minute`() {
        assertEquals(15_000L, EmergencyRetrySchedule.delayAfter(1))
        assertEquals(30_000L, EmergencyRetrySchedule.delayAfter(2))
        assertEquals(60_000L, EmergencyRetrySchedule.delayAfter(3))
        assertEquals(60_000L, EmergencyRetrySchedule.delayAfter(50))
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }
}

/** In-memory stand-in for the Room DAO, same rules as its queries. */
private class FakeEmergencyOutboxDao : EmergencyOutboxDao {
    val rows = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, EmergencyOutboxEntity>()
    private val changes = MutableStateFlow(0)

    override suspend fun insertAll(rows: List<EmergencyOutboxEntity>) {
        rows.forEach { this.rows.putIfAbsent(it.alertId to it.recipientId, it) }
        changes.value++
    }

    override suspend fun due(now: Long, notBefore: Long) = rows.values
        .filter { it.deliveredAt == null && it.nextAttemptAt <= now && it.createdAt > notBefore }
        .sortedBy { it.createdAt }

    override suspend fun nextAttemptAt(notBefore: Long) = rows.values
        .filter { it.deliveredAt == null && it.createdAt > notBefore }
        .minOfOrNull { it.nextAttemptAt }

    override suspend fun recordAttempt(alertId: String, recipientId: String, at: Long, nextAt: Long, error: String?) {
        rows.computeIfPresent(alertId to recipientId) { _, r ->
            r.copy(attemptCount = r.attemptCount + 1, lastAttemptAt = at, nextAttemptAt = nextAt, lastError = error)
        }
        changes.value++
    }

    override suspend fun markDelivered(alertId: String, recipientId: String, at: Long): Int {
        val r = rows[alertId to recipientId] ?: return 0
        if (r.deliveredAt != null) return 0
        rows[alertId to recipientId] = r.copy(deliveredAt = at)
        changes.value++
        return 1
    }

    override fun observeAlert(alertId: String): Flow<List<EmergencyOutboxEntity>> =
        changes.map { rows.values.filter { it.alertId == alertId } }

    override suspend fun deleteOlderThan(cutoff: Long): Int {
        val old = rows.filterValues { it.createdAt < cutoff }.keys
        old.forEach { rows.remove(it) }
        return old.size
    }
}
