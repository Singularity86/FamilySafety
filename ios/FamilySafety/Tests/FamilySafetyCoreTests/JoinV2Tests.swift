import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// Join v2 (§8.2–§8.4): the joiner's request built by `JoinFlow.prepare` and checked by
/// `JoinV2Verifier`, the way Android's JoinV2Test exercises the same rules.
final class JoinV2Tests: XCTestCase {

    private let now: Int64 = 1_800_000_000_000

    private func identity(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }

    private func crypto(_ id: FamilySafeIdentity) -> E2EECrypto {
        E2EECrypto(localMemberId: id.memberId, localEd25519SecretKey: id.ed25519SecretKey64, localX25519SecretKey: id.x25519PrivateKey)
    }

    private struct Setup {
        let inviter: FamilySafeIdentity
        let joiner: FamilySafeIdentity
        var book: InviteBook
        let invite: InviteCode
    }

    private func setup() throws -> Setup {
        let inviter = try identity("inviter"), joiner = try identity("joiner")
        let group = GroupDefinition(groupId: "g-1", groupName: "Fam", createdAtEpochMs: 1, creatorMemberId: inviter.memberId,
                                    members: [FamilyMember(memberId: inviter.memberId, displayName: "Mom",
                                                           ed25519PublicKey: inviter.ed25519PublicKeyHex,
                                                           x25519PublicKey: inviter.x25519PublicKeyHex, addedAtEpochMs: 1)],
                                    version: 1)
        var book = InviteBook()
        let invite = try book.issue(group: group, inviter: inviter, inviterName: "Mom", now: now)
        return Setup(inviter: inviter, joiner: joiner, book: book, invite: invite)
    }

    private func message(_ pending: PendingJoin) throws -> JoinRequestV2Message {
        try JSONDecoder().decode(JoinRequestV2Message.self, from: pending.requestJSON)
    }

    // MARK: - Vectors (§12)

    func test_vectors_matchAndroid() {
        XCTAssertEqual(JoinV2.fingerprint(ed25519: [UInt8](repeating: 1, count: 32), x25519: [UInt8](repeating: 2, count: 32)), "367443")
        XCTAssertEqual(JoinV2.displayFingerprint("367443"), "367 443")
        XCTAssertEqual(Hex.encode(JoinV2.mac(secret: [UInt8](repeating: 7, count: 32), message: Array("JOIN:v2:test".utf8))),
                       "d6855d7c0a3be87c363e0c5d5f3df3ac5ce67f4ad242ee224e932b34ae09efe4")
    }

    // MARK: - Round trip

    func test_v2InviteGetsAV2RequestThatTheInviterVerifies() throws {
        let s = try setup()
        XCTAssertEqual(s.invite.v, "2")
        let encoded = try XCTUnwrap(InviteCode.decode(base64: try s.invite.encodedBase64()))
        let pending = try JoinFlow.prepare(identity: s.joiner, displayName: "Kid", invite: encoded, requestId: "req-1", timestampMs: now)
        XCTAssertEqual(pending.inviterX25519, s.inviter.x25519PublicKeyHex)
        XCTAssertEqual(pending.requestId, "req-1")

        let wire = try message(pending)
        XCTAssertEqual(wire.v, 2)
        // The broker sees neither the name nor the request ID.
        let text = String(decoding: pending.requestJSON, as: UTF8.self)
        XCTAssertFalse(text.contains("Kid"))
        XCTAssertFalse(text.contains("req-1"))

        guard case .verified(let request) = JoinV2Verifier.verify(message: wire, now: now, invites: s.book, inviterCrypto: crypto(s.inviter)) else {
            return XCTFail("expected verified")
        }
        XCTAssertEqual(request.displayName, "Kid")
        XCTAssertEqual(request.requesterId, s.joiner.memberId)
        XCTAssertEqual(request.inviteId, s.invite.inviteId)
        let signed = String(decoding: try XCTUnwrap(request.signingBytesV2()), as: UTF8.self)
        XCTAssertEqual(signed, "JOIN:v2:req-1:\(s.joiner.memberId):g-1:\(now):\(s.joiner.ed25519PublicKeyHex):\(s.joiner.x25519PublicKeyHex):\(s.invite.inviteId!):\(JoinV2.sha256Hex("Kid"))")
        XCTAssertEqual(JoinFlow.fingerprint(of: s.joiner).count, 6)
    }

    func test_v1InviteStillGetsAV1Request() throws {
        let s = try setup()
        let v1 = InviteCode(groupId: "g-1", groupName: "Fam", inviterMemberId: s.inviter.memberId, inviterName: "Mom", timestamp: "1")
        let pending = try JoinFlow.prepare(identity: s.joiner, displayName: "Kid", invite: v1, requestId: "req-1", timestampMs: now)
        XCTAssertNil(pending.inviterX25519)
        let request = try JSONDecoder().decode(JoinRequest.self, from: pending.requestJSON)
        XCTAssertNil(request.inviteId)
        XCTAssertTrue(String(decoding: try XCTUnwrap(request.signedBytes()), as: UTF8.self).hasPrefix("JOIN:v1:"))
    }

    // MARK: - Joiner refuses bad invites

    func test_joinerRefusesExpiredOrForeignInvites() throws {
        let s = try setup()
        XCTAssertThrowsError(try JoinFlow.prepare(identity: s.joiner, displayName: "Kid", invite: s.invite,
                                                  timestampMs: now + JoinV2.inviteLifetimeMs + 1))
        let impostor = try identity("impostor")
        let forged = InviteCode(groupId: "g-1", groupName: "Fam", inviterMemberId: s.inviter.memberId, inviterName: "Mom", timestamp: "1",
                                v: "2", inviteId: s.invite.inviteId, inviteSecret: s.invite.inviteSecret,
                                inviterEd25519: impostor.ed25519PublicKeyHex, inviterX25519: impostor.x25519PublicKeyHex,
                                expiresAt: s.invite.expiresAt)
        XCTAssertThrowsError(try JoinFlow.prepare(identity: s.joiner, displayName: "Kid", invite: forged, timestampMs: now)) {
            XCTAssertEqual($0 as? JoinV2Error, .invalidInvite("Invalid invite code — the inviter's keys don't match"))
        }
    }

    // MARK: - Inviter checks, one at a time

    func test_inviterDropsUnknownUsedOrExpiredInvites() throws {
        var s = try setup()
        let wire = try message(try JoinFlow.prepare(identity: s.joiner, displayName: "Kid", invite: s.invite, timestampMs: now))
        XCTAssertEqual(JoinV2Verifier.verify(message: wire, now: now, invites: InviteBook(), inviterCrypto: crypto(s.inviter)), .rejected("unknown invite"))
        XCTAssertEqual(JoinV2Verifier.verify(message: wire, now: now + JoinV2.inviteLifetimeMs + 1, invites: s.book, inviterCrypto: crypto(s.inviter)),
                       .rejected("invite expired"))
        s.book.markUsed(s.invite.inviteId!)
        XCTAssertEqual(JoinV2Verifier.verify(message: wire, now: now, invites: s.book, inviterCrypto: crypto(s.inviter)), .rejected("invite already used"))
    }

    func test_inviterDropsARequestMadeWithoutTheSecret() throws {
        let s = try setup()
        // Someone who saw the inviteId on the broker but not the QR's secret.
        var guessed = s.invite
        guessed = InviteCode(groupId: guessed.groupId, groupName: guessed.groupName, inviterMemberId: guessed.inviterMemberId,
                             inviterName: guessed.inviterName, timestamp: guessed.timestamp, v: "2", inviteId: guessed.inviteId,
                             inviteSecret: Data(repeating: 9, count: 32).base64EncodedString(),
                             inviterEd25519: guessed.inviterEd25519, inviterX25519: guessed.inviterX25519, expiresAt: guessed.expiresAt)
        let attacker = try identity("attacker")
        let wire = try message(try JoinFlow.prepare(identity: attacker, displayName: "Kid", invite: guessed, timestampMs: now))
        XCTAssertEqual(JoinV2Verifier.verify(message: wire, now: now, invites: s.book, inviterCrypto: crypto(s.inviter)),
                       .rejected("not made with this invite's secret"))
    }

    func test_inviterDropsAnEnvelopeNamingSomeoneElse() throws {
        let s = try setup()
        let real = try message(try JoinFlow.prepare(identity: s.joiner, displayName: "Kid", invite: s.invite, timestampMs: now))
        let attacker = try identity("attacker")
        // Outer keys swapped to the attacker's, envelope still the joiner's: sender check fires before decrypting.
        let swapped = JoinRequestV2Message(inviteId: real.inviteId, requesterEd25519: attacker.ed25519PublicKeyHex,
                                           requesterX25519: attacker.x25519PublicKeyHex, encryptedRequest: real.encryptedRequest)
        XCTAssertEqual(JoinV2Verifier.verify(message: swapped, now: now, invites: s.book, inviterCrypto: crypto(s.inviter)),
                       .rejected("envelope sender is not the requester's key"))
    }

    func test_inviterDropsATamperedName() throws {
        let s = try setup()
        let secret = try XCTUnwrap(Data(base64Encoded: s.invite.inviteSecret!).map(Array.init))
        // A request whose MAC and signature cover "Kid" but whose displayName says "Dana".
        let good = try JoinFlow.makeRequest(identity: s.joiner, displayName: "Kid", invite: s.invite, timestampMs: now, inviteId: s.invite.inviteId)
        let renamed = JoinRequest(requestId: good.requestId, requesterId: good.requesterId, displayName: "Dana",
                                  ed25519PublicKey: good.ed25519PublicKey, x25519PublicKey: good.x25519PublicKey,
                                  groupId: good.groupId, timestampMs: good.timestampMs, signature: good.signature, inviteId: good.inviteId)
        let payload = JoinRequestV2Payload(request: renamed, mac: Hex.encode(JoinV2.mac(secret: secret, message: try XCTUnwrap(good.signingBytesV2()))))
        let envelope = try crypto(s.joiner).encrypt(plaintext: String(decoding: try JSONEncoder().encode(payload), as: UTF8.self),
                                                    recipientMemberId: s.inviter.memberId, recipientX25519PublicKey: s.inviter.x25519PublicKey)
        let wire = JoinRequestV2Message(inviteId: s.invite.inviteId!, requesterEd25519: s.joiner.ed25519PublicKeyHex,
                                        requesterX25519: s.joiner.x25519PublicKeyHex, encryptedRequest: String(decoding: envelope, as: UTF8.self))
        XCTAssertEqual(JoinV2Verifier.verify(message: wire, now: now, invites: s.book, inviterCrypto: crypto(s.inviter)),
                       .rejected("signature does not verify"))
    }

    // MARK: - Joiner pins the inviter's encryption key

    func test_joinerIgnoresAnApprovalNamingAnotherInviterX25519() throws {
        let s = try setup()
        let pending = try JoinFlow.prepare(identity: s.joiner, displayName: "Kid", invite: s.invite, timestampMs: now)
        let other = try identity("other")
        let group = GroupDefinition(groupId: "g-1", groupName: "Fam", createdAtEpochMs: 1, creatorMemberId: s.inviter.memberId, members: [], version: 2)
        let envelope = try crypto(s.inviter).encrypt(plaintext: String(decoding: try JSONEncoder().encode(group), as: UTF8.self),
                                                     recipientMemberId: s.joiner.memberId, recipientX25519PublicKey: s.joiner.x25519PublicKey)
        let approval = try JSONEncoder().encode(JoinApprovalMessage(inviterEd25519PublicKey: s.inviter.ed25519PublicKeyHex,
                                                                    inviterX25519PublicKey: other.x25519PublicKeyHex,
                                                                    encryptedGroupDefinition: String(decoding: envelope, as: UTF8.self)))
        XCTAssertEqual(JoinFlow.evaluate(payload: approval, identity: s.joiner, pending: pending),
                       .ignore("approval key does not match the scanned invite"))
    }

    // MARK: - Invite book

    func test_inviteBookIsPrunedAndCapped() throws {
        var s = try setup()
        let group = GroupDefinition(groupId: "g-1", groupName: "Fam", createdAtEpochMs: 1, creatorMemberId: s.inviter.memberId, members: [], version: 1)
        for i in 0..<30 { _ = try s.book.issue(group: group, inviter: s.inviter, inviterName: "Mom", now: now + Int64(i)) }
        XCTAssertEqual(s.book.invites.count, InviteBook.cap)
        _ = try s.book.issue(group: group, inviter: s.inviter, inviterName: "Mom", now: now + JoinV2.inviteLifetimeMs + 100)
        XCTAssertEqual(s.book.invites.count, 1)
        XCTAssertEqual(Set(s.book.invites.map(\.inviteId)).count, s.book.invites.count)
    }
}
