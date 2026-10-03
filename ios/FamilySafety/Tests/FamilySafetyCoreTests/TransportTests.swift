import XCTest
@testable import FamilySafetyCore

/// Pure-logic transport tests — topic naming, backoff/timeout math, and the offline
/// queues. None of this needs a live broker. The actual §14 Phase 2 acceptance test (two
/// simulators seeing each other's presence over the real EMQX broker) needs credentials
/// nobody but the project owner has and is NOT exercised here — see IOS_PORT_SPEC.md §4.
final class TransportTests: XCTestCase {

    // MARK: - Topics

    func test_topics_matchAndroidMqttConfigExactly() {
        XCTAssertEqual(Topics.generateClientId(memberId: "m1"), "familysafe_m1")
        XCTAssertEqual(Topics.location(memberId: "m1"), "familysafe/m1/location")
        XCTAssertEqual(Topics.locationInbox(memberId: "m1"), "familysafe/m1/location_inbox")
        XCTAssertEqual(Topics.presence(memberId: "m1"), "familysafe/m1/presence")
        XCTAssertEqual(Topics.groupAck(groupId: "g1"), "familysafe/group/g1/ack")
        XCTAssertEqual(Topics.syncRequest(memberId: "m1"), "familysafe/m1/sync_request")
        XCTAssertEqual(Topics.groupSyncInbox(memberId: "m1"), "familysafe/m1/group_sync")
        XCTAssertEqual(Topics.removalVote(memberId: "m1"), "familysafe/m1/removal_vote")
        XCTAssertEqual(Topics.joinRequest(inviterMemberId: "m1"), "familysafe/m1/join_request")
        XCTAssertEqual(Topics.joinApproval(joinerMemberId: "m1"), "familysafe/m1/join_approval")
        XCTAssertEqual(Topics.replicationRequest(memberId: "m1"), "familysafe/m1/replication/request")
        XCTAssertEqual(Topics.replicationData(memberId: "m1"), "familysafe/m1/replication/data")
        XCTAssertEqual(Topics.replicationAnnounceInbox(memberId: "m1"), "familysafe/m1/replication/announce")
        XCTAssertEqual(Topics.chat(memberId: "m1"), "familysafe/m1/chat")
        XCTAssertEqual(Topics.chatReceipt(memberId: "m1"), "familysafe/m1/chat/receipt")
        XCTAssertEqual(Topics.chatRead(memberId: "m1"), "familysafe/m1/chat/read")
        XCTAssertEqual(Topics.fileManifest(groupId: "g1"), "familysafe/group/g1/files/manifest")
        XCTAssertEqual(Topics.fileChunk(groupId: "g1", fileId: "f1", chunkIndex: 3), "familysafe/group/g1/files/chunk/f1/3")
        XCTAssertEqual(Topics.fileChunkWildcard(groupId: "g1"), "familysafe/group/g1/files/chunk/#")
        XCTAssertEqual(Topics.fileRequest(memberId: "m1"), "familysafe/m1/files/request")
        XCTAssertEqual(Topics.fileRepair(memberId: "m1"), "familysafe/m1/files/repair")
        XCTAssertEqual(Topics.fileAvailability(memberId: "m1"), "familysafe/m1/files/availability")
        XCTAssertEqual(Topics.vaultContainer(groupId: "g1"), "familysafe/group/g1/vault/container")
        XCTAssertEqual(Topics.vaultChunk(groupId: "g1", fileId: "f1", chunkIndex: 2), "familysafe/group/g1/vault/chunk/f1/2")
        XCTAssertEqual(Topics.vaultChunkWildcard(groupId: "g1"), "familysafe/group/g1/vault/chunk/#")
        XCTAssertEqual(Topics.vaultRepair(memberId: "m1"), "familysafe/m1/vault/repair")
    }

    func test_topics_isControlPlaneTopic() {
        XCTAssertTrue(Topics.isControlPlaneTopic("familysafe/m1/group_sync"))
        XCTAssertTrue(Topics.isControlPlaneTopic("familysafe/m1/sync_request"))
        XCTAssertTrue(Topics.isControlPlaneTopic("familysafe/m1/join_request"))
        XCTAssertTrue(Topics.isControlPlaneTopic("familysafe/m1/join_approval"))
        XCTAssertTrue(Topics.isControlPlaneTopic("familysafe/m1/removal_vote"))
        XCTAssertTrue(Topics.isControlPlaneTopic("familysafe/group/g1/ack"))

        XCTAssertFalse(Topics.isControlPlaneTopic("familysafe/m1/chat"))
        XCTAssertFalse(Topics.isControlPlaneTopic("familysafe/m1/location_inbox"))
        XCTAssertFalse(Topics.isControlPlaneTopic("familysafe/group/g1/files/manifest"))
        // "ends with /ack" alone isn't enough — must also contain "/group/".
        XCTAssertFalse(Topics.isControlPlaneTopic("familysafe/m1/ack"))
    }

    func test_topics_ownSubscriptionTopics_includesGroupTopicsOnlyWhenGroupIdProvided() {
        let withoutGroup = Topics.ownSubscriptionTopics(memberId: "m1")
        XCTAssertFalse(withoutGroup.contains("familysafe/group/g1/ack"))
        XCTAssertTrue(withoutGroup.contains("familysafe/m1/chat"))
        XCTAssertTrue(withoutGroup.contains("familysafe/m1/vault/repair"))

        let withGroup = Topics.ownSubscriptionTopics(memberId: "m1", groupId: "g1")
        XCTAssertTrue(withGroup.contains("familysafe/group/g1/ack"))
        XCTAssertTrue(withGroup.contains("familysafe/group/g1/files/manifest"))
        XCTAssertTrue(withGroup.contains("familysafe/group/g1/vault/container"))
        // The three vault topics must be subscribed unconditionally by every device (§5, §6.8).
        XCTAssertTrue(withGroup.contains("familysafe/group/g1/vault/chunk/#"))
        XCTAssertTrue(withGroup.contains("familysafe/m1/vault/repair"))
    }

    // MARK: - TransportTiming

    func test_reconnectDelay_exponentialBackoffCappedAtFiveMinutes() {
        XCTAssertEqual(TransportTiming.reconnectDelay(attempt: 1), 5)
        XCTAssertEqual(TransportTiming.reconnectDelay(attempt: 2), 10)
        XCTAssertEqual(TransportTiming.reconnectDelay(attempt: 3), 20)
        XCTAssertEqual(TransportTiming.reconnectDelay(attempt: 4), 40)
        XCTAssertEqual(TransportTiming.reconnectDelay(attempt: 5), 80)
        XCTAssertEqual(TransportTiming.reconnectDelay(attempt: 6), 160)
        XCTAssertEqual(TransportTiming.reconnectDelay(attempt: 7), 300) // would be 320 uncapped
        XCTAssertEqual(TransportTiming.reconnectDelay(attempt: 20), 300)
    }

    func test_connectAttemptTimeout_is35Seconds() {
        // Android: CONNECTION_TIMEOUT(30s) * 1000 + 5000ms = 35s exactly (§4).
        XCTAssertEqual(TransportTiming.connectAttemptTimeout, 35)
    }

    func test_isConnectingStale() {
        let start = Date(timeIntervalSince1970: 1_000_000)
        let justUnderThreshold = start.addingTimeInterval(3 * 35 + 10 - 1)
        let overThreshold = start.addingTimeInterval(3 * 35 + 10 + 1)
        XCTAssertFalse(TransportTiming.isConnectingStale(connectingSince: start, now: justUnderThreshold))
        XCTAssertTrue(TransportTiming.isConnectingStale(connectingSince: start, now: overThreshold))
    }

    // MARK: - PendingMessageQueue

    func test_pendingQueue_routesByControlPlaneVsBulk() {
        let queue = PendingMessageQueue()
        let now = Date()
        queue.enqueue(PendingMessage(topic: "familysafe/m1/group_sync", payload: Data(), qos: 1, retained: false, enqueuedAt: now))
        queue.enqueue(PendingMessage(topic: "familysafe/m1/chat", payload: Data(), qos: 1, retained: false, enqueuedAt: now))
        XCTAssertEqual(queue.controlCount, 1)
        XCTAssertEqual(queue.bulkCount, 1)
    }

    func test_pendingQueue_dropsOldestOnOverflow() {
        let queue = PendingMessageQueue()
        let now = Date()
        for i in 0..<(PendingMessageQueue.controlCapacity + 5) {
            queue.enqueue(PendingMessage(
                topic: "familysafe/m1/group_sync", payload: Data([UInt8(i % 256)]), qos: 1, retained: false, enqueuedAt: now
            ))
        }
        XCTAssertEqual(queue.controlCount, PendingMessageQueue.controlCapacity)
    }

    func test_pendingQueue_drainAllExpiresOldMessagesAndClearsQueues() {
        let queue = PendingMessageQueue()
        let old = Date(timeIntervalSince1970: 0)
        let fresh = Date(timeIntervalSince1970: 10_000)
        let now = fresh.addingTimeInterval(10)

        queue.enqueue(PendingMessage(topic: "familysafe/m1/group_sync", payload: Data("old".utf8), qos: 1, retained: false, enqueuedAt: old))
        queue.enqueue(PendingMessage(topic: "familysafe/m1/chat", payload: Data("fresh".utf8), qos: 1, retained: false, enqueuedAt: fresh))

        let drained = queue.drainAll(now: now)
        XCTAssertEqual(drained.count, 1)
        XCTAssertEqual(drained.first?.payload, Data("fresh".utf8))
        XCTAssertEqual(queue.controlCount, 0)
        XCTAssertEqual(queue.bulkCount, 0)
    }

    func test_pendingQueue_drainAllReturnsControlPlaneFirst() {
        let queue = PendingMessageQueue()
        let now = Date()
        queue.enqueue(PendingMessage(topic: "familysafe/m1/chat", payload: Data("bulk".utf8), qos: 1, retained: false, enqueuedAt: now))
        queue.enqueue(PendingMessage(topic: "familysafe/m1/group_sync", payload: Data("control".utf8), qos: 1, retained: false, enqueuedAt: now))

        let drained = queue.drainAll(now: now)
        XCTAssertEqual(drained.first?.payload, Data("control".utf8))
        XCTAssertEqual(drained.last?.payload, Data("bulk".utf8))
    }

    // MARK: - MqttTransport (construction + offline publish path only — no live broker)

    func test_mqttTransport_clientIdAndInitialState() {
        let transport = MqttTransport(memberId: "m1", credentials: BrokerCredentials(username: "u", password: "p"))
        XCTAssertEqual(transport.clientId, "familysafe_m1")
        XCTAssertEqual(transport.connectionState, .disconnected)
        XCTAssertFalse(transport.isConnected)
    }

    func test_mqttTransport_publishWhileDisconnectedQueuesInsteadOfCrashing() {
        let transport = MqttTransport(memberId: "m1", credentials: BrokerCredentials(username: "u", password: "p"))
        let delivered = transport.publish(topic: "familysafe/m1/chat", payload: Data("hi".utf8), qos: .qos1)
        XCTAssertFalse(delivered)
        XCTAssertEqual(transport.pendingBulkCount, 1)
    }
}
