import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// The 1.15.8 spec changes and Android #92: the newcomer grace (§3.1), replay windows owned by an
/// identity, sequenced join approvals, and the files re-send (§6.7).
final class Spec1158Tests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private func body(_ seq: Int64) -> SequencedBody { SequencedBody(seq: seq, ts: now, body: "{}") }

    private func identity(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }

    // MARK: Newcomer grace

    func test_bareBodiesStillCountForTenMinutesAfterTheFirstSequencedOne() {
        var w = ReplayWindow()
        XCTAssertEqual(w.check(sender: "s", body: body(now * 1000), nowMs: now), .accept)
        XCTAssertEqual(w.check(sender: "s", body: nil, nowMs: now + 1), .accept, "overtaken by a sequenced one")
        XCTAssertFalse(w.refusesUnsequenced("s", nowMs: now + ReplayWindow.sequencingGraceMs))
        XCTAssertEqual(w.check(sender: "s", body: nil, nowMs: now + ReplayWindow.sequencingGraceMs + 1), .unsequencedAfterSequenced)
    }

    func test_laterSequencedBodiesDoNotExtendTheGrace() {
        var w = ReplayWindow()
        _ = w.check(sender: "s", body: body(now * 1000), nowMs: now)
        let later = now + 9 * 60_000
        XCTAssertEqual(w.check(sender: "s", body: SequencedBody(seq: later * 1000, ts: later, body: "{}"), nowMs: later), .accept)
        XCTAssertTrue(w.refusesUnsequenced("s", nowMs: now + ReplayWindow.sequencingGraceMs + 1))
    }

    func test_theGraceSurvivesARestart_butAWindowSavedBeforeItGetsNone() throws {
        var w = ReplayWindow()
        _ = w.check(sender: "s", body: body(now * 1000), nowMs: now)
        var restored = try JSONDecoder().decode(ReplayWindow.self, from: JSONEncoder().encode(w))
        XCTAssertEqual(restored.check(sender: "s", body: nil, nowMs: now + 60_000), .accept)

        let old = #"{"floor":{},"seen":{"s":[\#(now * 1000)]},"hwm":{"s":\#(now * 1000)}}"#
        var legacy = try JSONDecoder().decode(ReplayWindow.self, from: Data(old.utf8))
        XCTAssertEqual(legacy.check(sender: "s", body: nil, nowMs: now + 1), .unsequencedAfterSequenced)
        XCTAssertEqual(legacy.check(sender: "s", body: body(now * 1000), nowMs: now + 1), .replay, "the rest of it was kept")
    }

    // MARK: Windows belong to an identity

    func test_anotherIdentityDropsTheWindows_theSameKeepsThem_andTheCounterStays() {
        var ledger = ProtocolLedger(observingSinceMs: now)
        XCTAssertTrue(ledger.belong(to: "AA"), "saved before owners were kept")
        _ = ledger.window.check(sender: "s", body: body(now * 1000), nowMs: now)
        let seq = ledger.counter.next(nowMs: now)
        XCTAssertFalse(ledger.belong(to: "aa"), "the same identity, restored from its phrase")
        XCTAssertTrue(ledger.window.hasSequenced("s"))
        XCTAssertTrue(ledger.belong(to: "bb"))
        XCTAssertFalse(ledger.window.hasSequenced("s"))
        XCTAssertEqual(ledger.owner, "bb")
        XCTAssertGreaterThan(ledger.counter.next(nowMs: now), seq)
    }

    func test_theEngineDropsAnotherIdentitysWindowsAtStart() async throws {
        let old = try identity("old"), new = try identity("new")
        var ledger = ProtocolLedger(observingSinceMs: now)
        ledger.belong(to: old.ed25519PublicKeyHex)
        _ = ledger.window.check(sender: "inviter", body: body(now * 1000), nowMs: now)
        let slot = InMemorySlot()
        try slot.writeJSON(ledger)
        let store = GroupStateStore(localMemberId: new.memberId, persistence: InMemoryGroupStatePersistence())
        let t = now
        let engine = GroupSyncEngine(identity: new, store: store, outbox: NullOutbox(), ledgerSlot: slot, now: { t })
        let started = await engine.ledger
        XCTAssertFalse(started.window.hasSequenced("inviter"))
        XCTAssertEqual(try slot.readJSON(ProtocolLedger.self)?.owner, new.ed25519PublicKeyHex.lowercased())
    }

    // MARK: Join approvals

    func test_aSequencedApprovalFromAndroid1158IsAccepted() throws {
        let inviter = try identity("inviter"), joiner = try identity("joiner")
        let invite = InviteCode(groupId: "g-1", groupName: "Fam", inviterMemberId: inviter.memberId, inviterName: "Mom", timestamp: "1")
        let request = try JoinFlow.makeRequest(identity: joiner, displayName: "Kid", invite: invite, requestId: "r", timestampMs: 5)
        let pending = try PendingJoin(invite: invite, request: request)
        let member = { (id: FamilySafeIdentity, name: String) in
            FamilyMember(memberId: id.memberId, displayName: name, ed25519PublicKey: id.ed25519PublicKeyHex,
                         x25519PublicKey: id.x25519PublicKeyHex, addedAtEpochMs: 1)
        }
        let family = GroupDefinition(groupId: "g-1", groupName: "Fam", createdAtEpochMs: 1, creatorMemberId: inviter.memberId,
                                     members: [member(inviter, "Mom"), member(joiner, "Kid")], version: 2)
        let sequenced = String(decoding: try JSONEncoder().encode(
            SequencedBody(seq: now * 1000, ts: now, body: String(decoding: try JSONEncoder().encode(family), as: UTF8.self))), as: UTF8.self)
        let crypto = E2EECrypto(localMemberId: inviter.memberId, localEd25519SecretKey: inviter.ed25519SecretKey64,
                                localX25519SecretKey: inviter.x25519PrivateKey)
        let envelope = try crypto.encrypt(plaintext: sequenced, recipientMemberId: joiner.memberId, recipientX25519PublicKey: joiner.x25519PublicKey)
        let approval = try JSONEncoder().encode(JoinApprovalMessage(
            inviterEd25519PublicKey: inviter.ed25519PublicKeyHex, inviterX25519PublicKey: inviter.x25519PublicKeyHex,
            encryptedGroupDefinition: String(decoding: envelope, as: UTF8.self)))
        XCTAssertEqual(JoinFlow.evaluate(payload: approval, identity: joiner, pending: pending), .approved(family))
    }

    // MARK: Files

    func test_anEmptyHoldingsAnnouncementIsSent() {
        var library = FileLibrary()
        XCTAssertEqual(library.announcement(me: "me", now: now)?.holdings, [])
    }

    func test_onlyOurOwnCompleteOlderUnlistedFilesNeedTheManifestAgain() throws {
        var library = FileLibrary()
        let file = { (id: String, by: String, at: Int64) in
            SharedFile(fileId: id, name: id, mimeType: "text/plain", sizeBytes: 1, contentHash: "h-\(id)",
                       uploaderMemberId: by, uploadedAt: at, chunkCount: 1)
        }
        let old = now - FileLibrary.manifestResendMinAgeMs
        for f in [file("ours", "me", old), file("fresh", "me", now - 1), file("theirs", "ben", old), file("listed", "me", old)] {
            library.addOwn(f, keyVersion: 3)
        }
        let a = FileHoldingsAnnouncement(announcerId: "dana", holdings: [
            FileHolding(fileId: "listed", contentHash: "h-listed", state: FileHolding.partial, chunksHeld: 0, chunkCount: 1)
        ], timestamp: now)
        XCTAssertEqual(library.sharedButUnknown(to: a, me: "me", now: now), ["ours"])
    }
}

private struct NullOutbox: GroupSyncOutbox {
    func send(topic: String, payload: Data, qos: Int, retained: Bool) async {}
}
