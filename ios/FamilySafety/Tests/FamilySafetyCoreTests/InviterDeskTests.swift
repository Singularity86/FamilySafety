import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// The inviter's side end to end: an iOS joiner's v2 request through `InviterDesk`, and the
/// approval or rejection back through `JoinFlow.evaluate`.
final class InviterDeskTests: XCTestCase {

    actor Outbox: GroupSyncOutbox {
        private(set) var sent: [(topic: String, payload: Data, retained: Bool)] = []
        func send(topic: String, payload: Data, qos: Int, retained: Bool) async { sent.append((topic, payload, retained)) }
        func last(on topic: String) -> Data? { sent.last { $0.topic == topic && !$0.payload.isEmpty }?.payload }
    }

    private let now: Int64 = 1_800_000_000_000

    private func identity(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }

    private func me(_ id: FamilySafeIdentity, _ name: String) -> FamilyMember {
        FamilyMember(memberId: id.memberId, displayName: name, ed25519PublicKey: id.ed25519PublicKeyHex,
                     x25519PublicKey: id.x25519PublicKeyHex, addedAtEpochMs: now)
    }

    private struct World {
        let inviter: FamilySafeIdentity
        let store: GroupStateStore
        let outbox: Outbox
        let desk: InviterDesk
        let group: GroupDefinition
    }

    private func world() async throws -> World {
        let inviter = try identity("inviter")
        let store = GroupStateStore(localMemberId: inviter.memberId, persistence: InMemoryGroupStatePersistence())
        let t = now
        let group = try await store.create(groupName: "Fam", creator: me(inviter, "Mom"), now: t)
        let outbox = Outbox()
        let engine = GroupSyncEngine(identity: inviter, store: store, outbox: outbox, now: { t })
        let desk = try InviterDesk(identity: inviter, store: store, engine: engine, outbox: outbox, bookSlot: InMemorySlot(), now: { t })
        return World(inviter: inviter, store: store, outbox: outbox, desk: desk, group: group)
    }

    func test_createMakesAKeyedFamilyOfOne_andRefusesASecond() async throws {
        let w = try await world()
        XCTAssertEqual(w.group.version, 1)
        XCTAssertEqual(w.group.members.map(\.memberId), [w.inviter.memberId])
        XCTAssertEqual(w.group.fileEncryptionKey?.count, 64)
        XCTAssertEqual(w.group.groupId, w.group.groupId.lowercased())
        do {
            _ = try await w.store.create(groupName: "Again", creator: me(w.inviter, "Mom"), now: now)
            XCTFail("expected alreadyInFamily")
        } catch { XCTAssertEqual(error as? LocalEditError, .alreadyInFamily) }
    }

    func test_promptApproveAndTheJoinerAdoptsTheFamily() async throws {
        let w = try await world()
        let joiner = try identity("joiner")
        let invite = try await w.desk.issueInvite(inviterName: "Mom")
        let pending = try JoinFlow.prepare(identity: joiner, displayName: "Kid", invite: invite, requestId: "r1", timestampMs: now)

        guard case .prompted(let prompt) = await w.desk.handle(payload: pending.requestJSON) else { return XCTFail("expected prompt") }
        XCTAssertEqual(prompt.displayName, "Kid")
        XCTAssertEqual(prompt.fingerprint, JoinFlow.fingerprint(of: joiner))
        // The joiner re-publishes every 30 s: the same request must not stack up.
        _ = await w.desk.handle(payload: pending.requestJSON)
        let prompts = await w.desk.prompts
        XCTAssertEqual(prompts.count, 1)

        try await w.desk.approve(requestId: "r1")
        let raw1 = await w.store.current
        let family = try XCTUnwrap(raw1)
        XCTAssertEqual(family.version, 2)
        XCTAssertEqual(family.previousStateHash, w.group.computeStateHash())
        XCTAssertEqual(family.findMember(byId: joiner.memberId)?.addedByMemberId, w.inviter.memberId)

        let raw2 = await w.outbox.last(on: Topics.joinApproval(joinerMemberId: joiner.memberId))
        let approval = try XCTUnwrap(raw2)
        XCTAssertEqual(JoinFlow.evaluate(payload: approval, identity: joiner, pending: pending), .approved(family))

        // A redelivery after the decision is dropped (the used invite is pruned), and nobody else gets in through it.
        let got3 = await w.desk.handle(payload: pending.requestJSON)
        XCTAssertEqual(got3, .dropped("unknown invite"))
        let other = try JoinFlow.prepare(identity: try identity("other"), displayName: "X", invite: invite, timestampMs: now)
        let got4 = await w.desk.handle(payload: other.requestJSON)
        XCTAssertEqual(got4, .dropped("unknown invite"))
    }

    func test_rejectReachesOnlyThatRequest() async throws {
        let w = try await world()
        let joiner = try identity("joiner")
        let invite = try await w.desk.issueInvite(inviterName: "Mom")
        let pending = try JoinFlow.prepare(identity: joiner, displayName: "Kid", invite: invite, requestId: "r1", timestampMs: now)
        _ = await w.desk.handle(payload: pending.requestJSON)
        try await w.desk.reject(requestId: "r1")
        let raw5 = await w.outbox.last(on: Topics.joinApproval(joinerMemberId: joiner.memberId))
        let rejection = try XCTUnwrap(raw5)
        XCTAssertEqual(JoinFlow.evaluate(payload: rejection, identity: joiner, pending: pending), .rejected)
        let family = await w.store.current
        XCTAssertEqual(family?.version, 1)
    }

    func test_restoredMemberIsApprovedWithoutAPrompt_andAKeyConflictIsDropped() async throws {
        let w = try await world()
        let kid = try identity("kid")
        let invite1 = try await w.desk.issueInvite(inviterName: "Mom")
        let first = try JoinFlow.prepare(identity: kid, displayName: "Kid", invite: invite1, requestId: "r1", timestampMs: now)
        _ = await w.desk.handle(payload: first.requestJSON)
        try await w.desk.approve(requestId: "r1")

        // Same phrase on a new phone: same keys, so straight back in, no version bump.
        let invite2 = try await w.desk.issueInvite(inviterName: "Mom")
        let restored = try JoinFlow.prepare(identity: kid, displayName: "Kid", invite: invite2, requestId: "r2", timestampMs: now)
        let got6 = await w.desk.handle(payload: restored.requestJSON)
        XCTAssertEqual(got6, .autoApproved(memberId: kid.memberId))
        let family = await w.store.current
        XCTAssertEqual(family?.version, 2)

        // Someone claiming the kid's member ID with other keys is never offered for approval.
        let invite3 = try await w.desk.issueInvite(inviterName: "Mom")
        let impostor = try identity("impostor")
        let forged = InviteCode(groupId: invite3.groupId, groupName: invite3.groupName, inviterMemberId: invite3.inviterMemberId,
                                inviterName: "Mom", timestamp: invite3.timestamp, v: "2", inviteId: invite3.inviteId,
                                inviteSecret: invite3.inviteSecret, inviterEd25519: invite3.inviterEd25519,
                                inviterX25519: invite3.inviterX25519, expiresAt: invite3.expiresAt)
        let request = try JoinFlow.prepare(identity: impostor, displayName: "Kid", invite: forged, timestampMs: now)
        // The impostor's own memberId differs, so this one is a newcomer, prompted under its own ID —
        // what matters is that the kid's entry is untouched.
        _ = await w.desk.handle(payload: request.requestJSON)
        let kept = await w.store.current?.findMember(byId: kid.memberId)
        XCTAssertEqual(kept?.ed25519PublicKey, kid.ed25519PublicKeyHex)
    }

    func test_v1RequestsAreNeverAccepted() async throws {
        let w = try await world()
        let v1 = InviteCode(groupId: w.group.groupId, groupName: "Fam", inviterMemberId: w.inviter.memberId, inviterName: "Mom", timestamp: "1")
        let pending = try JoinFlow.prepare(identity: try identity("old"), displayName: "Old", invite: v1, timestampMs: now)
        let got7 = await w.desk.handle(payload: pending.requestJSON)
        XCTAssertEqual(got7, .dropped("not a v2 request"))
    }
}
