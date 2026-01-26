package com.example.familysafety.replication

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.storage.ChatMessageDao
import com.example.familysafety.storage.ChatMessageEntity
import com.example.familysafety.storage.DataSummary
import com.example.familysafety.storage.LocationHistoryRepository
import com.example.familysafety.storage.MessageStatus
import com.example.familysafety.storage.MessageType
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReplicationManagerTest {

    private lateinit var replicationManager: ReplicationManager
    private lateinit var mockGroupStateManager: GroupStateManager
    private lateinit var mockLocationRepository: LocationRepository
    private lateinit var mockLocationHistoryRepository: LocationHistoryRepository
    private lateinit var mockChatMessageDao: ChatMessageDao
    private lateinit var mockE2eeManager: E2EEManager

    private val testDispatcher = StandardTestDispatcher()

    // Test data
    private val localMemberId = "local_member"
    private val localEd25519Key = "a".repeat(64)
    private val localX25519Key = "b".repeat(64)

    private val peerMemberId = "peer_member"
    private val peerEd25519Key = "c".repeat(64)
    private val peerX25519Key = "d".repeat(64)

    private val localMember = FamilyMember(
        memberId = localMemberId,
        displayName = "Local User",
        ed25519PublicKey = localEd25519Key,
        x25519PublicKey = localX25519Key,
        addedAtEpochMs = System.currentTimeMillis()
    )

    private val peerMember = FamilyMember(
        memberId = peerMemberId,
        displayName = "Peer User",
        ed25519PublicKey = peerEd25519Key,
        x25519PublicKey = peerX25519Key,
        addedAtEpochMs = System.currentTimeMillis()
    )

    private val groupDefinition = GroupDefinition(
        groupId = "test_group_id",
        groupName = "Test Family",
        createdAtEpochMs = System.currentTimeMillis(),
        creatorMemberId = localMemberId,
        members = setOf(localMember, peerMember),
        version = 1
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockGroupStateManager = mockk(relaxed = true)
        mockLocationRepository = mockk(relaxed = true)
        mockLocationHistoryRepository = mockk(relaxed = true)
        mockChatMessageDao = mockk(relaxed = true)
        mockE2eeManager = mockk(relaxed = true)

        // Setup group state manager
        every { mockGroupStateManager.localMember } returns MutableStateFlow(localMember)
        every { mockGroupStateManager.groupDefinition } returns MutableStateFlow(groupDefinition)

        // Setup default returns
        coEvery { mockLocationHistoryRepository.getNewestTimestamp(any()) } returns null
        coEvery { mockChatMessageDao.getAllConversationIds() } returns emptyList()

        replicationManager = ReplicationManager(
            groupStateManager = mockGroupStateManager,
            locationRepository = mockLocationRepository,
            locationHistoryRepository = mockLocationHistoryRepository,
            chatMessageDao = mockChatMessageDao,
            e2eeManager = mockE2eeManager
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `syncState starts as Idle`() = runTest {
        assertEquals(SyncState.Idle, replicationManager.syncState.first())
    }

    @Test
    fun `requestFullSync updates state to Syncing then Synced`() = runTest {
        var capturedPayloads = mutableListOf<Pair<String, ByteArray>>()
        replicationManager.setMqttPublisher { topic, payload, qos ->
            capturedPayloads.add(topic to payload)
            true
        }

        replicationManager.requestFullSync()
        advanceUntilIdle()

        // Should have sent requests to peer
        assertTrue(capturedPayloads.isNotEmpty())
        assertEquals(SyncState.Synced, replicationManager.syncState.first())
    }

    @Test
    fun `requestFullSync does nothing without group`() = runTest {
        every { mockGroupStateManager.groupDefinition } returns MutableStateFlow(null)

        var capturedPayloads = mutableListOf<Pair<String, ByteArray>>()
        replicationManager.setMqttPublisher { topic, payload, qos ->
            capturedPayloads.add(topic to payload)
            true
        }

        replicationManager.requestFullSync()
        advanceUntilIdle()

        assertTrue(capturedPayloads.isEmpty())
        assertEquals(SyncState.Idle, replicationManager.syncState.first())
    }

    @Test
    fun `handleReplicationRequest responds with location data`() = runTest {
        val testLocations = listOf(
            MemberLocation(
                memberId = peerMemberId,
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10f,
                timestamp = 1000L
            )
        )

        coEvery {
            mockLocationHistoryRepository.getLocationsAfter(peerMemberId, any())
        } returns testLocations

        var capturedPayload: ByteArray? = null
        replicationManager.setMqttPublisher { topic, payload, qos ->
            capturedPayload = payload
            true
        }

        val request = """
            {
                "requestId": "req123",
                "requesterId": "$peerMemberId",
                "dataType": "LOCATION_HISTORY",
                "targetMemberId": "$peerMemberId",
                "afterTimestamp": 0,
                "limit": 500
            }
        """.trimIndent()

        replicationManager.handleReplicationRequest(request, peerMemberId)
        advanceUntilIdle()

        assertNotNull(capturedPayload)
        val responseStr = String(capturedPayload!!)
        assertTrue(responseStr.contains("req123"))
        assertTrue(responseStr.contains("LOCATION_HISTORY"))
    }

    @Test
    fun `handleReplicationRequest does not respond when no data`() = runTest {
        coEvery { mockLocationHistoryRepository.getLocationsAfter(any(), any()) } returns emptyList()

        var capturedPayload: ByteArray? = null
        replicationManager.setMqttPublisher { topic, payload, qos ->
            capturedPayload = payload
            true
        }

        val request = """
            {
                "requestId": "req123",
                "requesterId": "$peerMemberId",
                "dataType": "LOCATION_HISTORY",
                "targetMemberId": "$peerMemberId",
                "afterTimestamp": 0,
                "limit": 500
            }
        """.trimIndent()

        replicationManager.handleReplicationRequest(request, peerMemberId)
        advanceUntilIdle()

        // Should not have sent a response
        assertNull(capturedPayload)
    }

    @Test
    fun `handleReplicationResponse stores location data`() = runTest {
        val response = """
            {
                "requestId": "req123",
                "senderId": "$peerMemberId",
                "dataType": "LOCATION_HISTORY",
                "locations": [
                    {
                        "memberId": "$peerMemberId",
                        "latitude": 37.7749,
                        "longitude": -122.4194,
                        "accuracy": 10.0,
                        "timestamp": 1000
                    }
                ],
                "hasMore": false
            }
        """.trimIndent()

        replicationManager.handleReplicationResponse(response, peerMemberId)
        advanceUntilIdle()

        verify {
            mockLocationRepository.updateMemberLocations(
                locations = any(),
                isReplicated = true,
                replicatedFrom = peerMemberId
            )
        }
    }

    @Test
    fun `handleReplicationResponse stores chat messages`() = runTest {
        val response = """
            {
                "requestId": "req123",
                "senderId": "$peerMemberId",
                "dataType": "CHAT_MESSAGES",
                "messages": [
                    {
                        "messageId": "msg123",
                        "conversationId": "$localMemberId:$peerMemberId",
                        "senderId": "$peerMemberId",
                        "recipientId": "$localMemberId",
                        "content": "Hello",
                        "messageType": "TEXT",
                        "status": "SENT",
                        "timestamp": 1000,
                        "isOutgoing": false,
                        "isReadLocally": false
                    }
                ],
                "hasMore": false
            }
        """.trimIndent()

        replicationManager.handleReplicationResponse(response, peerMemberId)
        advanceUntilIdle()

        coVerify { mockChatMessageDao.insertAll(any()) }
    }

    @Test
    fun `replicateLocation sends to all peers`() = runTest {
        val location = MemberLocation(
            memberId = localMemberId,
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10f,
            timestamp = System.currentTimeMillis()
        )

        var capturedTopics = mutableListOf<String>()
        replicationManager.setMqttPublisher { topic, payload, qos ->
            capturedTopics.add(topic)
            true
        }

        replicationManager.replicateLocation(location)
        advanceUntilIdle()

        // Should have sent to peer (not to self)
        assertEquals(1, capturedTopics.size)
        assertTrue(capturedTopics[0].contains(peerMemberId))
    }

    @Test
    fun `replicateChatMessage sends to all peers`() = runTest {
        val message = ChatMessageEntity(
            messageId = "msg123",
            conversationId = "$localMemberId:$peerMemberId",
            senderId = localMemberId,
            recipientId = peerMemberId,
            content = "Hello",
            messageType = MessageType.TEXT,
            status = MessageStatus.SENT,
            isOutgoing = true
        )

        var capturedTopics = mutableListOf<String>()
        replicationManager.setMqttPublisher { topic, payload, qos ->
            capturedTopics.add(topic)
            true
        }

        replicationManager.replicateChatMessage(message)
        advanceUntilIdle()

        assertEquals(1, capturedTopics.size)
        assertTrue(capturedTopics[0].contains(peerMemberId))
    }

    @Test
    fun `replicateLocation does nothing without publisher`() = runTest {
        val location = MemberLocation(
            memberId = localMemberId,
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10f,
            timestamp = System.currentTimeMillis()
        )

        // Don't set publisher
        replicationManager.replicateLocation(location)
        advanceUntilIdle()

        // Should complete without error
    }

    @Test
    fun `announceDataAvailability broadcasts summary`() = runTest {
        coEvery { mockLocationHistoryRepository.getDataSummary() } returns mapOf(
            localMemberId to DataSummary(
                oldestTimestamp = 1000L,
                newestTimestamp = 5000L,
                count = 10
            )
        )
        coEvery { mockChatMessageDao.getAllConversationIds() } returns listOf("conv1")
        coEvery { mockChatMessageDao.getNewestTimestamp("conv1") } returns 5000L
        coEvery { mockChatMessageDao.getConversation("conv1") } returns listOf(
            ChatMessageEntity(
                conversationId = "conv1",
                senderId = localMemberId,
                recipientId = peerMemberId,
                content = "test",
                timestamp = 3000L,
                isOutgoing = true
            )
        )

        var capturedTopic: String? = null
        var capturedPayload: ByteArray? = null
        replicationManager.setMqttPublisher { topic, payload, qos ->
            capturedTopic = topic
            capturedPayload = payload
            true
        }

        replicationManager.announceDataAvailability()
        advanceUntilIdle()

        assertNotNull(capturedTopic)
        assertTrue(capturedTopic!!.contains("announce") || capturedTopic!!.contains("replication"))

        val payloadStr = String(capturedPayload!!)
        assertTrue(payloadStr.contains(localMemberId))
    }

    @Test
    fun `handleDataAvailabilityAnnouncement requests missing data`() = runTest {
        coEvery { mockLocationHistoryRepository.getNewestTimestamp(any()) } returns null

        var sentRequests = mutableListOf<String>()
        replicationManager.setMqttPublisher { topic, payload, qos ->
            sentRequests.add(String(payload))
            true
        }

        val announcement = """
            {
                "announcerId": "$peerMemberId",
                "locationDataSummary": [
                    {
                        "memberId": "$peerMemberId",
                        "oldestTimestamp": 1000,
                        "newestTimestamp": 5000,
                        "count": 10
                    }
                ],
                "chatDataSummary": []
            }
        """.trimIndent()

        replicationManager.handleDataAvailabilityAnnouncement(announcement, peerMemberId)
        advanceUntilIdle()

        // Should have sent a request for the missing data
        assertTrue(sentRequests.any { it.contains("LOCATION_HISTORY") })
    }

    @Test
    fun `handleDataAvailabilityAnnouncement skips if data is current`() = runTest {
        // We already have data up to timestamp 5000
        coEvery { mockLocationHistoryRepository.getNewestTimestamp(peerMemberId) } returns 5000L

        var sentRequests = mutableListOf<String>()
        replicationManager.setMqttPublisher { topic, payload, qos ->
            sentRequests.add(String(payload))
            true
        }

        val announcement = """
            {
                "announcerId": "$peerMemberId",
                "locationDataSummary": [
                    {
                        "memberId": "$peerMemberId",
                        "oldestTimestamp": 1000,
                        "newestTimestamp": 5000,
                        "count": 10
                    }
                ],
                "chatDataSummary": []
            }
        """.trimIndent()

        replicationManager.handleDataAvailabilityAnnouncement(announcement, peerMemberId)
        advanceUntilIdle()

        // Should NOT have sent a request since we already have the data
        assertTrue(sentRequests.isEmpty())
    }
}
