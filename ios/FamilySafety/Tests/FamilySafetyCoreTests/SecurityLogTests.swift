import XCTest
@testable import FamilySafetyCore

/// Intrusion detection, phase 1: the on-phone security log and its report.
final class SecurityLogTests: XCTestCase {
    private let hour: Int64 = 3_600_000, t0: Int64 = 1_800_000_000_000

    func test_repeatsWithinAnHourAreGrouped_laterOnesStartAnewEntry() {
        var log = SecurityLog()
        log.record(.unknownSender, memberId: "x", detail: nil, now: t0)
        log.record(.unknownSender, memberId: "x", detail: nil, now: t0 + hour / 2)
        log.record(.unknownSender, memberId: "y", detail: nil, now: t0 + hour / 2)
        XCTAssertEqual(log.events.map(\.count), [2, 1])
        log.record(.unknownSender, memberId: "x", detail: nil, now: t0 + 2 * hour)
        XCTAssertEqual(log.events.count, 3)
    }

    func test_keeps30DaysAndACap() {
        var log = SecurityLog()
        log.record(.replayRefused, memberId: "x", detail: "replay", now: t0)
        log.record(.replayRefused, memberId: "x", detail: "replay", now: t0 + 31 * 24 * hour)
        XCTAssertEqual(log.events.count, 1, "the 31-day-old entry is gone")
        for i in 0..<400 { log.record(.unknownSender, memberId: "m\(i)", detail: nil, now: t0 + 31 * 24 * hour + Int64(i)) }
        XCTAssertEqual(log.events.count, SecurityLog.cap)
    }

    func test_reportNamesMembersShortensIdsAndCarriesNoKeys() {
        let alice = FamilyMember(memberId: String(repeating: "a", count: 32), displayName: "Alice",
                                 ed25519PublicKey: String(repeating: "1", count: 64), x25519PublicKey: String(repeating: "2", count: 64),
                                 addedAtEpochMs: 1)
        let group = GroupDefinition(groupId: "g", groupName: "The Starwinds", createdAtEpochMs: 1, creatorMemberId: alice.memberId,
                                    members: [alice], version: 3, fileEncryptionKey: String(repeating: "9", count: 64),
                                    removedMemberIds: [String(repeating: "b", count: 32)])
        var log = SecurityLog()
        log.record(.forgedSignature, memberId: alice.memberId, detail: "roster update", now: t0)
        log.record(.removedMemberActivity, memberId: String(repeating: "b", count: 32), detail: "presence", now: t0)
        log.record(.unknownSender, memberId: String(repeating: "c", count: 32), detail: nil, now: t0)
        log.record(.unknownSender, memberId: String(repeating: "c", count: 32), detail: nil, now: t0 + 1)
        let report = log.report(group: group, myMemberId: alice.memberId, appVersion: "1.0", now: t0)
        XCTAssertTrue(report.contains("Alice (aaaaaaaa)"))
        XCTAssertTrue(report.contains("bbbbbbbb, removed member"))
        XCTAssertTrue(report.contains("cccccccc, not in the family"))
        XCTAssertTrue(report.contains("×2"))
        XCTAssertTrue(report.contains("[HIGH]"))
        XCTAssertFalse(report.contains(String(repeating: "9", count: 64)), "no family key")
        XCTAssertFalse(report.contains(String(repeating: "1", count: 64)), "no member keys")
        XCTAssertFalse(report.contains(String(repeating: "a", count: 32)), "full IDs are shortened")
    }

    func test_recorderPersistsAndReloads() {
        let slot = InMemorySlot()
        SecurityRecorder(slot: slot, now: { 5 }).record(.impersonatedJoin, detail: "member ID with different keys")
        XCTAssertEqual(SecurityRecorder(slot: slot).snapshot.events.first?.kind, .impersonatedJoin)
    }
}
