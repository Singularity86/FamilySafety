import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// The joiner side of §8.3–§8.5, with the inviter played the way Android's InviteManager
/// builds its approval and rejection.
final class JoinFlowTests: XCTestCase {

    private func identity(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }

    private func crypto(_ id: FamilySafeIdentity) -> E2EECrypto {
        E2EECrypto(localMemberId: id.memberId, localEd25519SecretKey: id.ed25519SecretKey64, localX25519SecretKey: id.x25519PrivateKey)
    }

    private func entry(_ id: FamilySafeIdentity, _ name: String, x25519Hex: String? = nil) -> FamilyMember {
        FamilyMember(memberId: id.memberId, displayName: name, ed25519PublicKey: id.ed25519PublicKeyHex,
                     x25519PublicKey: x25519Hex ?? id.x25519PublicKeyHex, addedAtEpochMs: 1)
    }

    private struct Setup {
        let inviter: FamilySafeIdentity
        let joiner: FamilySafeIdentity
        let invite: InviteCode
        let request: JoinRequest
        let pending: PendingJoin
    }

    private func setup() throws -> Setup {
        let inviter = try identity("inviter"), joiner = try identity("joiner")
        let invite = InviteCode(groupId: "g-1", groupName: "Fam", inviterMemberId: inviter.memberId, inviterName: "Mom", timestamp: "1")
        let request = try JoinFlow.makeRequest(identity: joiner, displayName: "Kid", invite: invite, requestId: "req-1", timestampMs: 5)
        return Setup(inviter: inviter, joiner: joiner, invite: invite, request: request, pending: try PendingJoin(invite: invite, request: request))
    }

    /// What Android's InviteManager.approveJoinRequest publishes.
    private func approval(from inviter: FamilySafeIdentity, to joiner: FamilySafeIdentity, group: GroupDefinition) throws -> Data {
        let json = String(data: try JSONEncoder().encode(group), encoding: .utf8)!
        let envelope = try crypto(inviter).encrypt(plaintext: json, recipientMemberId: joiner.memberId, recipientX25519PublicKey: joiner.x25519PublicKey)
        return try JSONEncoder().encode(JoinApprovalMessage(
            inviterEd25519PublicKey: inviter.ed25519PublicKeyHex, inviterX25519PublicKey: inviter.x25519PublicKeyHex,
            encryptedGroupDefinition: String(data: envelope, encoding: .utf8)!))
    }

    private func rejection(from inviter: FamilySafeIdentity, to joiner: FamilySafeIdentity, requestId: String) throws -> Data {
        let body = String(data: try JSONEncoder().encode(JoinRejectionPayload(requestId: requestId, joinerMemberId: joiner.memberId, timestamp: 1)), encoding: .utf8)!
        let envelope = try crypto(inviter).encrypt(plaintext: body, recipientMemberId: joiner.memberId, recipientX25519PublicKey: joiner.x25519PublicKey)
        return try JSONEncoder().encode(JoinRejectionMessage(
            inviterEd25519PublicKey: inviter.ed25519PublicKeyHex, inviterX25519PublicKey: inviter.x25519PublicKeyHex,
            encryptedRejection: String(data: envelope, encoding: .utf8)!))
    }

    private func family(_ s: Setup, joinerEntry: FamilyMember? = nil) -> GroupDefinition {
        GroupDefinition(groupId: "g-1", groupName: "Fam", createdAtEpochMs: 1, creatorMemberId: s.inviter.memberId,
                        members: [entry(s.inviter, "Mom"), joinerEntry ?? entry(s.joiner, "Kid")], version: 2)
    }

    // MARK: - Request

    func test_request_isSignedOverAndroidSigningBytes() throws {
        let s = try setup()
        let bytes = try XCTUnwrap(s.request.signingBytes())
        let expected = "JOIN:v1:req-1:\(s.joiner.memberId):g-1:5:\(s.joiner.ed25519PublicKeyHex):\(s.joiner.x25519PublicKeyHex)"
        XCTAssertEqual(String(decoding: bytes, as: UTF8.self), expected)
        let sig = try XCTUnwrap(Hex.decode(try XCTUnwrap(s.request.signature)))
        XCTAssertTrue(SodiumRaw.signVerifyDetached(signature: sig, message: bytes, publicKey: s.joiner.ed25519PublicKey))
        // Keys travel as standard Base64, like Android's ByteArraySerializer.
        XCTAssertEqual(Data(base64Encoded: s.request.ed25519PublicKey).map(Array.init), s.joiner.ed25519PublicKey)
    }

    // MARK: - Approval and rejection

    func test_validApprovalIsAccepted() throws {
        let s = try setup()
        let decision = JoinFlow.evaluate(payload: try approval(from: s.inviter, to: s.joiner, group: family(s)), identity: s.joiner, pending: s.pending)
        XCTAssertEqual(decision, .approved(family(s)))
    }

    func test_approvalFromAnotherKeyIsIgnored() throws {
        let s = try setup()
        let impostor = try identity("impostor")
        let decision = JoinFlow.evaluate(payload: try approval(from: impostor, to: s.joiner, group: family(s)), identity: s.joiner, pending: s.pending)
        XCTAssertEqual(decision, .ignore("approval key does not match the scanned invite"))
    }

    func test_approvalListingThisDeviceWithASwappedKeyIsIgnored() throws {
        let s = try setup()
        let attacker = try identity("attacker")
        let swapped = family(s, joinerEntry: entry(s.joiner, "Kid", x25519Hex: attacker.x25519PublicKeyHex))
        let decision = JoinFlow.evaluate(payload: try approval(from: s.inviter, to: s.joiner, group: swapped), identity: s.joiner, pending: s.pending)
        XCTAssertEqual(decision, .ignore("delivered family lists this device with different keys"))
    }

    func test_approvalForAnotherGroupOrWithoutUsIsIgnored() throws {
        let s = try setup()
        let other = GroupDefinition(groupId: "g-2", groupName: "X", createdAtEpochMs: 1, creatorMemberId: s.inviter.memberId,
                                    members: [entry(s.inviter, "Mom"), entry(s.joiner, "Kid")], version: 1)
        XCTAssertEqual(JoinFlow.evaluate(payload: try approval(from: s.inviter, to: s.joiner, group: other), identity: s.joiner, pending: s.pending),
                       .ignore("group ID does not match the invite"))
        let withoutUs = GroupDefinition(groupId: "g-1", groupName: "Fam", createdAtEpochMs: 1, creatorMemberId: s.inviter.memberId,
                                        members: [entry(s.inviter, "Mom")], version: 1)
        XCTAssertEqual(JoinFlow.evaluate(payload: try approval(from: s.inviter, to: s.joiner, group: withoutUs), identity: s.joiner, pending: s.pending),
                       .ignore("delivered family does not include this device"))
    }

    func test_rejectionCountsOnlyForThisRequest() throws {
        let s = try setup()
        XCTAssertEqual(JoinFlow.evaluate(payload: try rejection(from: s.inviter, to: s.joiner, requestId: "req-1"), identity: s.joiner, pending: s.pending), .rejected)
        XCTAssertEqual(JoinFlow.evaluate(payload: try rejection(from: s.inviter, to: s.joiner, requestId: "old"), identity: s.joiner, pending: s.pending),
                       .ignore("rejection for a different request"))
        XCTAssertEqual(JoinFlow.evaluate(payload: Data(), identity: s.joiner, pending: s.pending), .ignore("retained clear"))
    }

    // MARK: - Coordinator

    actor Published: JoinPublisher {
        private(set) var messages: [(topic: String, payload: Data, retained: Bool)] = []
        func publish(topic: String, payload: Data, retained: Bool) async { messages.append((topic, payload, retained)) }
    }

    func test_coordinator_publishesRequest_thenSavesFamilyAndClearsRetained() async throws {
        let s = try setup()
        let published = Published()
        let store = GroupStateStore(localMemberId: s.joiner.memberId, persistence: InMemoryGroupStatePersistence())
        let coordinator = JoinCoordinator(identity: s.joiner, pending: s.pending, publisher: published, store: store, republishInterval: .seconds(60))
        await coordinator.start()
        try await Task.sleep(for: .milliseconds(50))
        let first = await published.messages
        XCTAssertEqual(first.first?.topic, Topics.joinRequest(inviterMemberId: s.inviter.memberId))
        XCTAssertEqual(first.first?.retained, true)

        let decision = await coordinator.handle(payload: try approval(from: s.inviter, to: s.joiner, group: family(s)))
        XCTAssertEqual(decision, .approved(family(s)))
        let saved = await store.current
        XCTAssertEqual(saved, family(s))
        let state = await coordinator.state
        XCTAssertEqual(state, .approved(family(s)))
        let clears = await published.messages.filter { $0.payload.isEmpty && $0.retained }.map(\.topic)
        XCTAssertEqual(Set(clears), [Topics.joinApproval(joinerMemberId: s.joiner.memberId), Topics.joinRequest(inviterMemberId: s.inviter.memberId)])
    }
}
