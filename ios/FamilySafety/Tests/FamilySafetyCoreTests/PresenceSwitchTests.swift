import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// 4.2 presence switch (spec §5) and the sudden-disconnect session start (§6.2), mirroring
/// Android's PresenceTopicsTest and PresenceSessionsTest.
final class PresenceSwitchTests: XCTestCase {
    private let me = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", gid = "g-1"

    func test_sessionSigningStringVector() {
        XCTAssertEqual(PresenceUpdate.sessionSigningPayload(memberId: "alice", timestamp: 1_700_000_000_000, sessionStart: 1_699_999_990_000),
                       "presence-session:v1:alice:1700000000000:1699999990000")
    }

    func test_sessionStartOnlyOnOnline_andOnlyWhenItVerifies() throws {
        let a = try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data("a".utf8))))
        let b = try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data("b".utf8))))
        let online = try PresenceUpdate.signed(memberId: a.memberId, isOnline: true, timestamp: 100, ed25519SecretKey64: a.ed25519SecretKey64, sessionStart: 90)
        XCTAssertEqual(online.verifiedSessionStart(publicKey: a.ed25519PublicKey), 90)
        XCTAssertNil(online.verifiedSessionStart(publicKey: b.ed25519PublicKey))
        let offline = try PresenceUpdate.signed(memberId: a.memberId, isOnline: false, timestamp: 100, ed25519SecretKey64: a.ed25519SecretKey64, sessionStart: 90)
        XCTAssertNil(offline.sessionStart, "the will and offline carry none")
        let future = try PresenceUpdate.signed(memberId: a.memberId, isOnline: true, timestamp: 100, ed25519SecretKey64: a.ed25519SecretKey64, sessionStart: 101)
        XCTAssertNil(future.verifiedSessionStart(publicKey: a.ed25519PublicKey), "a start after the update isn't a session")
    }

    func test_book_acceptsTheCurrentSessionsWillOnce_andNoOtherOlderUpdate() {
        var book = PresenceBook()
        XCTAssertTrue(book.accept(PresenceStatus(isOnline: true, timestamp: 100, sessionStart: 90), from: "d"))
        XCTAssertFalse(book.accept(PresenceStatus(isOnline: false, timestamp: 80), from: "d"), "an earlier session's will")
        XCTAssertFalse(book.accept(PresenceStatus(isOnline: true, timestamp: 95), from: "d"), "an older online is a replay")
        XCTAssertTrue(book.accept(PresenceStatus(isOnline: false, timestamp: 90), from: "d"), "this session's will")
        XCTAssertEqual(book.statuses["d"]?.isOnline, false)
        XCTAssertFalse(book.accept(PresenceStatus(isOnline: false, timestamp: 90), from: "d"), "taken once")
        XCTAssertTrue(book.accept(PresenceStatus(isOnline: true, timestamp: 200, sessionStart: 190), from: "d"), "reconnected")
        XCTAssertFalse(book.accept(PresenceStatus(isOnline: false, timestamp: 90), from: "d"), "a late will can't mark a reconnected member offline")
    }

    func test_book_onlineWithoutVerifiedSessionClearsIt() {
        var book = PresenceBook()
        _ = book.accept(PresenceStatus(isOnline: true, timestamp: 100, sessionStart: 90), from: "d")
        _ = book.accept(PresenceStatus(isOnline: true, timestamp: 110), from: "d")
        XCTAssertFalse(book.accept(PresenceStatus(isOnline: false, timestamp: 90), from: "d"))
    }

    func test_topics_beforeAndAfterTheSwitch() {
        let old = Topics.presence(memberId: me), group = PresenceTopics.group(groupId: gid, memberId: me)
        let before = PresenceTopics.publishTargets(memberId: me, groupId: gid, switched: false)
        XCTAssertEqual(before.map(\.topic), [old, group])
        XCTAssertEqual(before.map(\.retained), [true, false], "retained only on the will's topic")
        XCTAssertEqual(PresenceTopics.willTopic(memberId: me, groupId: gid, switched: false), old)
        XCTAssertEqual(PresenceTopics.staleTopic(memberId: me, groupId: gid, switched: false), group)
        let after = PresenceTopics.publishTargets(memberId: me, groupId: gid, switched: true)
        XCTAssertEqual(after.map(\.topic), [group])
        XCTAssertEqual(after.map(\.retained), [true])
        XCTAssertEqual(PresenceTopics.willTopic(memberId: me, groupId: gid, switched: true), group)
        XCTAssertEqual(PresenceTopics.staleTopic(memberId: me, groupId: gid, switched: true), old)
    }

    func test_senderOf_bothShapes_ownGroupOnly() {
        XCTAssertEqual(PresenceTopics.sender(of: "familysafe/abc/presence", groupId: gid), "abc")
        XCTAssertEqual(PresenceTopics.sender(of: "familysafe/group/g-1/presence/abc", groupId: gid), "abc")
        XCTAssertNil(PresenceTopics.sender(of: "familysafe/group/other/presence/abc", groupId: gid))
        XCTAssertNil(PresenceTopics.sender(of: "familysafe/group/g-1/presence/", groupId: gid))
        XCTAssertNil(PresenceTopics.sender(of: "familysafe/group/presence", groupId: gid))
        XCTAssertNil(PresenceTopics.sender(of: "familysafe/group/g-1/ack", groupId: gid))
    }

    func test_subscriptions_twoForAnyFamilySizeAfterTheSwitch() {
        let peers = (0..<12).map { String(format: "%032x", $0) }
        XCTAssertEqual(MqttTransport.subscriptionSet(memberId: me, groupId: gid, peerMemberIds: peers).count, 14)
        XCTAssertEqual(MqttTransport.subscriptionSet(memberId: me, groupId: gid, peerMemberIds: peers, presenceSwitched: true).count, 2)
    }
}
