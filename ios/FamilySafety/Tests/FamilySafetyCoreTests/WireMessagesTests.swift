import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// Round-trip and tolerance tests for the §6 wire structs not already covered by
/// VectorTests.swift (crypto core) or GroupTransitionValidatorTests.swift (group security).
/// Files (§6.7) and the vault (§6.8) are intentionally deferred to Phases 6 / 6.5, where
/// their structs are built alongside the encryption logic that drives them.
final class WireMessagesTests: XCTestCase {

    private func identity(_ tag: String) throws -> FamilySafeIdentity {
        let seed = Array(SHA512.hash(data: Data(tag.utf8)))
        return try FamilySafeKeyDerivation.deriveIdentity(seed: seed)
    }

    // MARK: - §6.1 MessageEnvelope padding

    func test_envelopePadding_roundsSizeToMultipleOf512() throws {
        let envelope = try EnvelopePadding.build(type: .locationUpdate, payload: "{}")
        let size = try JSONEncoder().encode(envelope).count
        XCTAssertEqual(size % EnvelopePadding.target, 0)
        XCTAssertGreaterThanOrEqual(size, EnvelopePadding.target)
    }

    func test_envelopePadding_largerPayloadStillRoundsUp() throws {
        let bigPayload = String(repeating: "x", count: 900)
        let envelope = try EnvelopePadding.build(type: .presenceUpdate, payload: bigPayload)
        let size = try JSONEncoder().encode(envelope).count
        XCTAssertEqual(size % EnvelopePadding.target, 0)
    }

    func test_envelopePadding_twoDifferentPayloadLengthsLandInSameOrDifferentBucketsCorrectly() throws {
        // Different plaintext lengths should still produce sizes that are exact multiples —
        // the whole point is that the *bucket*, not the exact size, is what an observer sees.
        let short = try JSONEncoder().encode(EnvelopePadding.build(type: .presenceUpdate, payload: "short")).count
        let long = try JSONEncoder().encode(EnvelopePadding.build(type: .presenceUpdate, payload: String(repeating: "y", count: 100))).count
        XCTAssertEqual(short % EnvelopePadding.target, 0)
        XCTAssertEqual(long % EnvelopePadding.target, 0)
    }

    func test_messageEnvelope_decodesAbsentPadAsEmptyString() throws {
        let json = #"{"type":"location_update","payload":"{}"}"#.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(MessageEnvelope.self, from: json)
        XCTAssertEqual(decoded.pad, "")
    }

    func test_messageEnvelope_doubleEncodedPayloadRoundTrip() throws {
        let location = LocationUpdate(memberId: "abc123", latitude: 1.5, longitude: -2.5, accuracy: 5.0, timestamp: 1000)
        let innerJSON = String(decoding: try JSONEncoder().encode(location), as: UTF8.self)
        let envelope = try EnvelopePadding.build(type: .locationUpdate, payload: innerJSON)

        let wireData = try JSONEncoder().encode(envelope)
        let decodedEnvelope = try JSONDecoder().decode(MessageEnvelope.self, from: wireData)
        XCTAssertEqual(decodedEnvelope.type, .locationUpdate)

        let decodedLocation = try JSONDecoder().decode(LocationUpdate.self, from: Data(decodedEnvelope.payload.utf8))
        XCTAssertEqual(decodedLocation, location)
    }

    // MARK: - §6.2 LocationUpdate / PresenceUpdate

    func test_locationUpdate_toleratesNullAndAbsentOptionalFields() throws {
        let withNulls = #"{"memberId":"m","latitude":0,"longitude":0,"accuracy":0,"timestamp":0,"speed":null,"bearing":null}"#
        let withoutFields = #"{"memberId":"m","latitude":0,"longitude":0,"accuracy":0,"timestamp":0}"#
        let a = try JSONDecoder().decode(LocationUpdate.self, from: Data(withNulls.utf8))
        let b = try JSONDecoder().decode(LocationUpdate.self, from: Data(withoutFields.utf8))
        XCTAssertNil(a.speed)
        XCTAssertNil(a.bearing)
        XCTAssertNil(b.speed)
        XCTAssertNil(b.bearing)
    }

    func test_presenceUpdate_missingProtocolVersionDefaultsToGeneration1() throws {
        let json = #"{"memberId":"m","isOnline":true,"timestamp":1000}"#
        let decoded = try JSONDecoder().decode(PresenceUpdate.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.protocolVersion, 1)
        XCTAssertNil(decoded.signature)
    }

    func test_presenceUpdate_explicitProtocolVersionRoundTrips() throws {
        let update = PresenceUpdate(memberId: "m", isOnline: false, timestamp: 500, signature: "deadbeef", protocolVersion: 2)
        let data = try JSONEncoder().encode(update)
        let decoded = try JSONDecoder().decode(PresenceUpdate.self, from: data)
        XCTAssertEqual(decoded, update)
    }

    func test_presenceSigningPayload_formatAndSignatureRoundTrip() throws {
        let alice = try identity("alice")
        let payload = PresenceUpdate.signingPayload(memberId: alice.memberId, isOnline: true, timestamp: 42)
        XCTAssertEqual(payload, "presence:\(alice.memberId):true:42")

        let signature = try SodiumRaw.signDetached(message: Array(payload.utf8), secretKey: alice.ed25519SecretKey64)
        XCTAssertTrue(SodiumRaw.signVerifyDetached(signature: signature, message: Array(payload.utf8), publicKey: alice.ed25519PublicKey))

        // A tampered isOnline must not verify against the original signature.
        let tamperedPayload = PresenceUpdate.signingPayload(memberId: alice.memberId, isOnline: false, timestamp: 42)
        XCTAssertFalse(SodiumRaw.signVerifyDetached(signature: signature, message: Array(tamperedPayload.utf8), publicKey: alice.ed25519PublicKey))
    }

    func test_sealedPresence_roundTrip() throws {
        let groupKeyHex = Hex.encode(Array(repeating: UInt8(0x42), count: 32))
        let presence = PresenceUpdate(memberId: "m", isOnline: true, timestamp: 100, signature: "ab", protocolVersion: 2)
        let inner = try JSONEncoder().encode(presence)
        let envelope = try EnvelopePadding.build(type: .presenceUpdate, payload: String(decoding: inner, as: UTF8.self))
        let envelopeJSON = try JSONEncoder().encode(envelope)

        let sealed = try PresenceSealing.seal(envelopeJSON: envelopeJSON, groupFileEncryptionKeyHex: groupKeyHex)
        let opened = try PresenceSealing.open(sealed, groupFileEncryptionKeyHex: groupKeyHex)
        XCTAssertEqual(opened, envelopeJSON)

        let reopenedEnvelope = try JSONDecoder().decode(MessageEnvelope.self, from: opened)
        let reopenedPresence = try JSONDecoder().decode(PresenceUpdate.self, from: Data(reopenedEnvelope.payload.utf8))
        XCTAssertEqual(reopenedPresence, presence)
    }

    func test_sealedPresence_wrongKeyFailsToOpen() throws {
        let correctKeyHex = Hex.encode(Array(repeating: UInt8(0x11), count: 32))
        let wrongKeyHex = Hex.encode(Array(repeating: UInt8(0x22), count: 32))
        let sealed = try PresenceSealing.seal(envelopeJSON: Data("hello".utf8), groupFileEncryptionKeyHex: correctKeyHex)
        XCTAssertThrowsError(try PresenceSealing.open(sealed, groupFileEncryptionKeyHex: wrongKeyHex))
    }

    // MARK: - §6.3 Chat

    func test_chatMessagePayload_roundTrip() throws {
        let message = ChatMessagePayload(messageId: "id1", content: "hi", messageType: .text, timestamp: 10, conversationId: "peer", replyToMessageId: nil)
        let decoded = try JSONDecoder().decode(ChatMessagePayload.self, from: try JSONEncoder().encode(message))
        XCTAssertEqual(decoded, message)
    }

    func test_chatLocationContent_embedsAsJSONStringInsideChatPayload() throws {
        let location = ChatLocationContent(latitude: 10.0, longitude: 20.0)
        let contentString = String(decoding: try JSONEncoder().encode(location), as: UTF8.self)
        let message = ChatMessagePayload(messageId: "id2", content: contentString, messageType: .location, timestamp: 20)

        let decodedMessage = try JSONDecoder().decode(ChatMessagePayload.self, from: try JSONEncoder().encode(message))
        let decodedLocation = try JSONDecoder().decode(ChatLocationContent.self, from: Data(decodedMessage.content.utf8))
        XCTAssertEqual(decodedLocation, location)
    }

    func test_deliveryReceipt_roundTrip() throws {
        let receipt = DeliveryReceipt(messageId: "id1", recipientId: "peer", status: .delivered, timestamp: 30)
        let decoded = try JSONDecoder().decode(DeliveryReceipt.self, from: try JSONEncoder().encode(receipt))
        XCTAssertEqual(decoded, receipt)
    }

    // MARK: - §6.4 Group sync wire structs

    func test_groupSyncMessage_quorumSignaturesDefaultToEmptyWhenAbsent() throws {
        let alice = try identity("alice")
        let group = GroupDefinition(
            groupId: "g", groupName: "Fam", createdAtEpochMs: 1, creatorMemberId: alice.memberId,
            members: [FamilyMember(memberId: alice.memberId, displayName: "A", ed25519PublicKey: alice.ed25519PublicKeyHex, x25519PublicKey: alice.x25519PublicKeyHex, addedAtEpochMs: 1)],
            version: 1
        )
        let groupJSON = String(decoding: try JSONEncoder().encode(group), as: UTF8.self)
        let json = """
        {"groupId":"g","version":1,"groupDefinition":\(groupJSON),"updaterMemberId":"\(alice.memberId)","changeType":"FULL_SYNC","timestamp":1,"signature":"ab"}
        """
        let decoded = try JSONDecoder().decode(GroupSyncMessage.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.quorumSignatures, [])
        XCTAssertNil(decoded.changedMemberId)
    }

    func test_groupSyncMessage_roundTripWithQuorumSignatures() throws {
        let alice = try identity("alice")
        let group = GroupDefinition(
            groupId: "g", groupName: "Fam", createdAtEpochMs: 1, creatorMemberId: alice.memberId,
            members: [], version: 1
        )
        let message = GroupSyncMessage(
            groupId: "g", version: 2, groupDefinition: group, updaterMemberId: alice.memberId,
            changeType: .memberRemoved, changedMemberId: "bob", timestamp: 5, signature: "sig",
            quorumSignatures: [QuorumSignatureEntry(voterMemberId: "v1", signature: "s1")]
        )
        let decoded = try JSONDecoder().decode(GroupSyncMessage.self, from: try JSONEncoder().encode(message))
        XCTAssertEqual(decoded, message)
    }

    func test_removalVoteMessage_signingPayloadFormat() {
        let payload = RemovalVoteMessage.signingPayload(groupId: "g1", targetMemberId: "m1", proposedVersion: 3, previousStateHash: "hash1")
        XCTAssertEqual(payload, "REMOVE_QUORUM:g1:m1:3:hash1")
    }

    func test_groupUpdateAck_roundTrip() throws {
        let ack = GroupUpdateAck(groupId: "g", version: 1, memberId: "m", timestamp: 1)
        let decoded = try JSONDecoder().decode(GroupUpdateAck.self, from: try JSONEncoder().encode(ack))
        XCTAssertEqual(decoded, ack)
    }

    func test_groupStateRefreshRequest_nullChangedMemberIdTolerance() throws {
        let json = #"{"groupId":"g","requesterMemberId":"m","minimumVersion":1,"changedMemberId":null,"reason":"stale","timestamp":1}"#
        let decoded = try JSONDecoder().decode(GroupStateRefreshRequest.self, from: Data(json.utf8))
        XCTAssertNil(decoded.changedMemberId)
    }

    // MARK: - §6.6 Replication

    func test_replicationRequestResponse_roundTrip() throws {
        let request = ReplicationRequest(requestId: "r1", requesterId: "m1", dataType: .chatMessages, afterTimestamp: 0, limit: 500, timestamp: 1)
        let decodedRequest = try JSONDecoder().decode(ReplicationRequest.self, from: try JSONEncoder().encode(request))
        XCTAssertEqual(decodedRequest, request)

        let message = ReplicatedMessage(
            messageId: "m1", conversationId: "c1", senderId: "s1", content: "hi",
            messageType: .text, status: .sent, timestamp: 1, isOutgoing: false
        )
        let response = ReplicationResponse(
            requestId: "r1", senderId: "s1", dataType: .chatMessages,
            messages: [message], hasMore: false, timestamp: 2
        )
        let decodedResponse = try JSONDecoder().decode(ReplicationResponse.self, from: try JSONEncoder().encode(response))
        XCTAssertEqual(decodedResponse, response)
    }

    func test_dataAvailabilityAnnouncement_roundTrip() throws {
        let announcement = DataAvailabilityAnnouncement(
            announcerId: "m1",
            locationDataSummary: [LocationHistorySummary(memberId: "m2", oldestTimestamp: 0, newestTimestamp: 10, count: 5)],
            chatDataSummary: [ChatHistorySummary(conversationId: "c1", oldestTimestamp: 0, newestTimestamp: 10, count: 3)],
            timestamp: 20
        )
        let decoded = try JSONDecoder().decode(DataAvailabilityAnnouncement.self, from: try JSONEncoder().encode(announcement))
        XCTAssertEqual(decoded, announcement)
    }

    // MARK: - §8 Onboarding / join

    func test_inviteCode_roundTripAndWhitespaceStripping() throws {
        let invite = InviteCode(groupId: "g1", groupName: "Fam", inviterMemberId: "m1", inviterName: "Alice", timestamp: "1700000000000")
        let encoded = try invite.encodedBase64()

        // Simulate a pasted code with line-wrapping and a trailing newline.
        let mangled = encoded.enumerated().map { i, c in i > 0 && i % 8 == 0 ? "\n\(c)" : String(c) }.joined() + "\n  "
        let decoded = InviteCode.decode(base64: mangled)
        XCTAssertEqual(decoded, invite)
    }

    func test_joinRequest_roundTripAndMemberIdDerivation() throws {
        let bob = try identity("bob")
        let ed25519Base64 = Data(bob.ed25519PublicKey).base64EncodedString()
        let x25519Base64 = Data(bob.x25519PublicKey).base64EncodedString()

        let request = JoinRequest(
            requestId: "r1", requesterId: bob.memberId, displayName: "Bob",
            ed25519PublicKey: ed25519Base64, x25519PublicKey: x25519Base64,
            groupId: "g1", timestampMs: 1000
        )
        let decoded = try JSONDecoder().decode(JoinRequest.self, from: try JSONEncoder().encode(request))
        XCTAssertEqual(decoded, request)

        // §8.4 step 1: deriveMemberId(request.ed25519PublicKey) must equal requesterId.
        guard let keyData = Data(base64Encoded: decoded.ed25519PublicKey) else {
            return XCTFail("expected valid base64")
        }
        let derived = MemberIdDerivation.deriveMemberId(fromEd25519PublicKeyBytes: Array(keyData))
        XCTAssertEqual(derived, decoded.requesterId)
    }

    func test_joinApprovalAndRejectionMessages_roundTrip() throws {
        let approval = JoinApprovalMessage(inviterEd25519PublicKey: "ed", inviterX25519PublicKey: "x2", encryptedGroupDefinition: "envelopeJSON")
        let decodedApproval = try JSONDecoder().decode(JoinApprovalMessage.self, from: try JSONEncoder().encode(approval))
        XCTAssertEqual(decodedApproval, approval)

        let rejection = JoinRejectionMessage(inviterEd25519PublicKey: "ed", inviterX25519PublicKey: "x2", encryptedRejection: "envelopeJSON")
        let decodedRejection = try JSONDecoder().decode(JoinRejectionMessage.self, from: try JSONEncoder().encode(rejection))
        XCTAssertEqual(decodedRejection, rejection)

        let payload = JoinRejectionPayload(requestId: "r1", joinerMemberId: "m1", timestamp: 1)
        let decodedPayload = try JSONDecoder().decode(JoinRejectionPayload.self, from: try JSONEncoder().encode(payload))
        XCTAssertEqual(decodedPayload, payload)
    }
}
