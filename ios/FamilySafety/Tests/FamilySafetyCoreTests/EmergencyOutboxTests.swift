import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// B3: crash alerts' per-member durable resend (spec §6.3 "Emergency (crash) alerts").
final class EmergencyOutboxTests: XCTestCase {
    private let t0: Int64 = 1_800_000_000_000

    private func alert(at t: Int64? = nil) throws -> ChatMessage {
        let ana = try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data("ana".utf8))))
        return ChatCodec.compose(content: "🚨 Ana may have been in an accident and did not respond.", to: nil,
                                 groupId: "g", identity: ana, now: t ?? t0)
    }

    func test_schedule_isImmediateThen15s30sThenEveryMinute() {
        XCTAssertEqual(EmergencyOutbox.delayAfter(attemptsMade: 1), 15_000)
        XCTAssertEqual(EmergencyOutbox.delayAfter(attemptsMade: 2), 30_000)
        XCTAssertEqual(EmergencyOutbox.delayAfter(attemptsMade: 3), 60_000)
        XCTAssertEqual(EmergencyOutbox.delayAfter(attemptsMade: 40), 60_000)
    }

    func test_everyCopyIsDueAtOnce_thenFollowsTheSchedule() throws {
        var box = EmergencyOutbox()
        let m = try alert()
        box.add(m, recipients: ["ben", "dana"])
        XCTAssertEqual(box.due(now: t0).map(\.recipientId), ["ben", "dana"], "stored, and due before the first send")
        box.recordAttempt(alertId: m.messageId, recipientId: "ben", now: t0)
        box.recordAttempt(alertId: m.messageId, recipientId: "dana", now: t0)
        XCTAssertTrue(box.due(now: t0 + 14_999).isEmpty)
        XCTAssertEqual(box.due(now: t0 + 15_000).count, 2)
        XCTAssertEqual(box.nextDueAtMs(now: t0), t0 + 15_000)
        XCTAssertEqual(box.due(now: t0 + 1, everything: true).count, 2, "a reconnect sends every copy still owed")
    }

    func test_oneMembersReceiptSettlesOnlyTheirCopy() throws {
        var box = EmergencyOutbox()
        let m = try alert()
        box.add(m, recipients: ["ben", "dana"])
        XCTAssertTrue(box.markDelivered(alertId: m.messageId, by: "dana", now: t0 + 5))
        XCTAssertFalse(box.markDelivered(alertId: m.messageId, by: "dana", now: t0 + 6), "already settled")
        XCTAssertFalse(box.markDelivered(alertId: m.messageId, by: "sam", now: t0 + 6), "never addressed to Sam")
        XCTAssertFalse(box.markDelivered(alertId: "other", by: "ben", now: t0 + 6))
        XCTAssertEqual(box.due(now: t0 + 60_000, everything: true).map(\.recipientId), ["ben"])
        let a = try XCTUnwrap(box.alerts[m.messageId])
        XCTAssertEqual(box.status(a.copies["dana"]!, of: a, now: t0 + 10), .received)
        XCTAssertEqual(box.status(a.copies["ben"]!, of: a, now: t0 + 10), .sending, "no attempt yet")
    }

    func test_statuses_waitingNotReachedAndLeft() throws {
        var box = EmergencyOutbox()
        let m = try alert()
        box.add(m, recipients: ["ben", "dana"])
        box.recordAttempt(alertId: m.messageId, recipientId: "ben", now: t0)
        box.markLeft(alertId: m.messageId, recipientId: "dana")
        var a = try XCTUnwrap(box.alerts[m.messageId])
        XCTAssertEqual(box.status(a.copies["ben"]!, of: a, now: t0 + 1000), .waiting)
        XCTAssertEqual(box.status(a.copies["dana"]!, of: a, now: t0 + 1000), .leftFamily)
        XCTAssertEqual(box.due(now: t0 + 60_000, everything: true).map(\.recipientId), ["ben"], "no resends to someone who left")
        a = try XCTUnwrap(box.alerts[m.messageId])
        XCTAssertEqual(box.status(a.copies["ben"]!, of: a, now: t0 + EmergencyOutbox.giveUpAfterMs), .notReached)
    }

    func test_givesUpAfter24Hours_andKeepsTheStatusForAWeek() throws {
        var box = EmergencyOutbox()
        let m = try alert()
        box.add(m, recipients: ["ben"])
        XCTAssertTrue(box.due(now: t0 + EmergencyOutbox.giveUpAfterMs, everything: true).isEmpty)
        XCTAssertNil(box.nextDueAtMs(now: t0 + EmergencyOutbox.giveUpAfterMs))
        XCTAssertNil(box.current(now: t0 + EmergencyOutbox.giveUpAfterMs))
        box.prune(now: t0 + EmergencyOutbox.giveUpAfterMs)
        XCTAssertNotNil(box.alerts[m.messageId])
        box.prune(now: t0 + EmergencyOutbox.keepMs)
        XCTAssertTrue(box.alerts.isEmpty)
    }

    func test_everyResendIsTheSameSignedMessage() throws {
        var box = EmergencyOutbox()
        let m = try alert()
        box.add(m, recipients: ["ben"])
        box.recordAttempt(alertId: m.messageId, recipientId: "ben", now: t0)
        let again = try XCTUnwrap(box.due(now: t0 + 15_000).first?.alert.message)
        // Compared decoded: JSONEncoder's key order isn't stable between runs.
        let decode = { (s: String) in try JSONDecoder().decode(ChatMessagePayload.self, from: Data(s.utf8)) }
        XCTAssertEqual(try decode(ChatCodec.plaintext(again)), try decode(ChatCodec.plaintext(m)), "same messageId, timestamp and signature")
        XCTAssertTrue(try ChatCodec.plaintext(m).contains("\"conversationId\":\"g\""), "an ordinary group chat message on the wire")
    }

    func test_survivesARestart() throws {
        var box = EmergencyOutbox()
        let m = try alert()
        box.add(m, recipients: ["ben"])
        box.recordAttempt(alertId: m.messageId, recipientId: "ben", now: t0)
        let slot = InMemorySlot()
        try slot.writeJSON(box)
        XCTAssertEqual(try slot.readJSON(EmergencyOutbox.self), box)
    }
}
