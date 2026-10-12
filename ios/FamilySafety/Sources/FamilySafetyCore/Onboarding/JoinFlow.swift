import Foundation

/// The joiner's side of joining a family (§8.3, §8.5). Ports OnboardingViewModel.sendJoinRequest
/// and MembershipViewModel's approval handling from Android 1.15.3.
///
/// The joiner authenticates the approval against the inviter's key hash from the scanned QR
/// (and, with a v2 invite, the inviter's X25519 key from it too), so a forged approval can't
/// plant a fake family. The inviter's checks live in `JoinV2Verifier`.
public enum JoinFlow {

    /// Everything to wait on `invite`: a v2 request for a v2 invite (the normal case), a v1
    /// request for a v1 invite from an older inviter. Throws `JoinV2Error.invalidInvite` for a
    /// v2 invite that is expired or whose keys aren't the inviter's.
    public static func prepare(
        identity: FamilySafeIdentity,
        displayName: String,
        invite: InviteCode,
        requestId: String = UUID().uuidString.lowercased(),
        timestampMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) throws -> PendingJoin {
        guard let scanned = try ScannedInviteV2.from(invite, now: timestampMs) else {
            let request = try makeRequest(identity: identity, displayName: displayName, invite: invite,
                                          requestId: requestId, timestampMs: timestampMs)
            return try PendingJoin(invite: invite, request: request)
        }
        let request = try makeRequest(identity: identity, displayName: displayName, invite: invite,
                                      requestId: requestId, timestampMs: timestampMs, inviteId: scanned.inviteId)
        guard let signed = request.signingBytesV2(), let inviterX = Hex.decode(scanned.inviterX25519Hex) else {
            throw JoinError.malformed
        }
        let payload = JoinRequestV2Payload(request: request, mac: Hex.encode(JoinV2.mac(secret: scanned.secret, message: signed)))
        let crypto = E2EECrypto(localMemberId: identity.memberId, localEd25519SecretKey: identity.ed25519SecretKey64,
                                localX25519SecretKey: identity.x25519PrivateKey)
        let envelope = try crypto.encrypt(plaintext: String(decoding: try JSONEncoder().encode(payload), as: UTF8.self),
                                          recipientMemberId: invite.inviterMemberId, recipientX25519PublicKey: inviterX)
        let message = JoinRequestV2Message(inviteId: scanned.inviteId, requesterEd25519: identity.ed25519PublicKeyHex,
                                           requesterX25519: identity.x25519PublicKeyHex,
                                           encryptedRequest: String(decoding: envelope, as: UTF8.self))
        return try PendingJoin(invite: invite, requestId: request.requestId,
                               requestJSON: JSONEncoder().encode(message), inviterX25519: scanned.inviterX25519Hex)
    }

    /// A signed join request for `invite`: v2 when `inviteId` is given, else v1. A v1 request
    /// is published as-is; a v2 one goes out wrapped by `prepare`.
    public static func makeRequest(
        identity: FamilySafeIdentity,
        displayName: String,
        invite: InviteCode,
        requestId: String = UUID().uuidString.lowercased(),
        timestampMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        inviteId: String? = nil
    ) throws -> JoinRequest {
        let unsigned = JoinRequest(
            requestId: requestId, requesterId: identity.memberId, displayName: displayName,
            ed25519PublicKey: Data(identity.ed25519PublicKey).base64EncodedString(),
            x25519PublicKey: Data(identity.x25519PublicKey).base64EncodedString(),
            groupId: invite.groupId, timestampMs: timestampMs, inviteId: inviteId
        )
        guard let bytes = unsigned.signedBytes() else { throw JoinError.malformed }
        try SodiumRaw.ensureInitialized()
        let signature = try SodiumRaw.signDetached(message: bytes, secretKey: identity.ed25519SecretKey64)
        return unsigned.signed(Hex.encode(signature))
    }

    /// The six digits this phone shows while waiting; the inviter's approval card shows the same.
    public static func fingerprint(of identity: FamilySafeIdentity) -> String {
        JoinV2.fingerprint(ed25519: identity.ed25519PublicKey, x25519: identity.x25519PublicKey)
    }

    /// What a message on our `join_approval` topic turned out to be.
    public enum Decision: Equatable {
        case approved(GroupDefinition)
        case rejected
        /// Not for us, forged, malformed, or a retained-clear — keep waiting.
        case ignore(String)
    }

    /// Verifies a `join_approval` payload. Mirrors MembershipViewModel.verifyApproval,
    /// JoinApprovalValidator.validate and verifyRejection, plus one iOS-only check: our own
    /// roster entry must carry *our* keys, so an approval minted for a swapped key is refused.
    public static func evaluate(
        payload: Data,
        identity: FamilySafeIdentity,
        pending: PendingJoin
    ) -> Decision {
        guard !payload.isEmpty else { return .ignore("retained clear") }
        let decoder = JSONDecoder()
        let crypto = E2EECrypto(localMemberId: identity.memberId, localEd25519SecretKey: identity.ed25519SecretKey64,
                                localX25519SecretKey: identity.x25519PrivateKey)

        if let approval = try? decoder.decode(JoinApprovalMessage.self, from: payload) {
            guard MemberIdDerivation.deriveMemberId(fromEd25519PublicKeyHex: approval.inviterEd25519PublicKey) == pending.inviterMemberId else {
                return .ignore("approval key does not match the scanned invite")
            }
            // The member ID pins only the signing key; a v2 QR pins the encryption key too.
            if let pinned = pending.inviterX25519, approval.inviterX25519PublicKey.lowercased() != pinned {
                return .ignore("approval key does not match the scanned invite")
            }
            guard let inviterX = Hex.decode(approval.inviterX25519PublicKey),
                  let inviterEd = Hex.decode(approval.inviterEd25519PublicKey),
                  let json = try? crypto.decrypt(envelopeJSON: Data(approval.encryptedGroupDefinition.utf8),
                                                 senderMemberId: pending.inviterMemberId,
                                                 senderX25519PublicKey: inviterX, senderEd25519PublicKey: inviterEd),
                  let group = try? decoder.decode(GroupDefinition.self, from: Data(json.utf8)) else {
                return .ignore("approval failed decryption or signature")
            }
            guard group.groupId == pending.groupId else { return .ignore("group ID does not match the invite") }
            guard let inviter = group.findMember(byId: pending.inviterMemberId),
                  inviter.ed25519PublicKey.lowercased() == approval.inviterEd25519PublicKey.lowercased() else {
                return .ignore("inviter is not in the delivered family with that key")
            }
            guard let me = group.findMember(byId: identity.memberId) else {
                return .ignore("delivered family does not include this device")
            }
            guard me.ed25519PublicKey.lowercased() == identity.ed25519PublicKeyHex,
                  me.x25519PublicKey.lowercased() == identity.x25519PublicKeyHex else {
                return .ignore("delivered family lists this device with different keys")
            }
            return .approved(group)
        }

        if let rejection = try? decoder.decode(JoinRejectionMessage.self, from: payload) {
            guard MemberIdDerivation.deriveMemberId(fromEd25519PublicKeyHex: rejection.inviterEd25519PublicKey) == pending.inviterMemberId,
                  pending.inviterX25519.map({ $0 == rejection.inviterX25519PublicKey.lowercased() }) ?? true,
                  let inviterX = Hex.decode(rejection.inviterX25519PublicKey),
                  let inviterEd = Hex.decode(rejection.inviterEd25519PublicKey),
                  let json = try? crypto.decrypt(envelopeJSON: Data(rejection.encryptedRejection.utf8),
                                                 senderMemberId: pending.inviterMemberId,
                                                 senderX25519PublicKey: inviterX, senderEd25519PublicKey: inviterEd),
                  let body = try? decoder.decode(JoinRejectionPayload.self, from: Data(json.utf8)) else {
                return .ignore("rejection not from the inviter")
            }
            // Anti-replay: only a rejection of *this* request counts.
            guard body.requestId == pending.requestId, body.joinerMemberId == identity.memberId else {
                return .ignore("rejection for a different request")
            }
            return .rejected
        }

        return .ignore("not an approval or rejection")
    }
}

public enum JoinError: Error, Equatable {
    case malformed
    case invalidInvite
}

/// Everything needed to resume waiting for an answer after the app restarts. The app
/// persists it (it holds no secrets) between `JoinCoordinator.start` and the decision.
public struct PendingJoin: Codable, Equatable, Sendable {
    public let groupId: String
    public let familyName: String
    public let inviterMemberId: String
    public let inviterName: String
    public let requestId: String
    /// Exactly what is published on the inviter's `join_request` topic: a plaintext v1
    /// `JoinRequest`, or a `JoinRequestV2Message` (whose `requestId` is only inside the
    /// encryption, which is why it's kept separately here for rejection matching).
    public let requestJSON: Data
    /// Hex, from a v2 QR. When set, an approval or rejection naming any other inviter X25519
    /// key is ignored. Nil for a v1 invite.
    public let inviterX25519: String?

    public init(invite: InviteCode, request: JoinRequest) throws {
        try self.init(invite: invite, requestId: request.requestId, requestJSON: JSONEncoder().encode(request), inviterX25519: nil)
    }

    public init(invite: InviteCode, requestId: String, requestJSON: Data, inviterX25519: String?) throws {
        groupId = invite.groupId
        familyName = invite.groupName
        inviterMemberId = invite.inviterMemberId
        inviterName = invite.inviterName
        self.requestId = requestId
        self.requestJSON = requestJSON
        self.inviterX25519 = inviterX25519?.lowercased()
    }
}

/// Where the coordinator publishes. `MqttTransport` satisfies it in the app.
public protocol JoinPublisher: Sendable {
    func publish(topic: String, payload: Data, retained: Bool) async
}

/// Drives one pending join: keeps the request on the broker until answered (re-publishing
/// it every `republishInterval`, as Android does, since a retained message can be cleared
/// by an inviter's earlier session), then saves the family and clears both retained
/// messages. Feed it every message that arrives on `familysafe/{me}/join_approval`.
public actor JoinCoordinator {
    public enum State: Equatable {
        case waiting
        case approved(GroupDefinition)
        case rejected
    }

    private let identity: FamilySafeIdentity
    private let pending: PendingJoin
    private let publisher: JoinPublisher
    private let store: GroupStateStore
    private let republishInterval: Duration
    private var republishTask: Task<Void, Never>?
    public private(set) var state: State = .waiting

    public init(identity: FamilySafeIdentity, pending: PendingJoin, publisher: JoinPublisher,
                store: GroupStateStore, republishInterval: Duration = .seconds(30)) {
        self.identity = identity
        self.pending = pending
        self.publisher = publisher
        self.store = store
        self.republishInterval = republishInterval
    }

    private var requestTopic: String { Topics.joinRequest(inviterMemberId: pending.inviterMemberId) }
    private var approvalTopic: String { Topics.joinApproval(joinerMemberId: identity.memberId) }

    /// Publishes the request now and keeps re-publishing it until a decision arrives.
    public func start() {
        guard state == .waiting, republishTask == nil else { return }
        let publisher = publisher, topic = requestTopic, payload = pending.requestJSON, interval = republishInterval
        republishTask = Task {
            while !Task.isCancelled {
                await publisher.publish(topic: topic, payload: payload, retained: true)
                try? await Task.sleep(for: interval)
            }
        }
    }

    public func stop() {
        republishTask?.cancel()
        republishTask = nil
    }

    /// Handles one message from our `join_approval` topic.
    @discardableResult
    public func handle(payload: Data) async -> JoinFlow.Decision {
        guard state == .waiting else { return .ignore("already decided") }
        let decision = JoinFlow.evaluate(payload: payload, identity: identity, pending: pending)
        switch decision {
        case .approved(let group):
            // Save first, clear after: a device that dies mid-join must still find either the
            // saved family or the retained approval (review F5's ordering).
            do { try await store.adopt(group) } catch { return .ignore("could not save the family yet") }
            stop()
            state = .approved(group)
            await clearRetained()
        case .rejected:
            stop()
            state = .rejected
            await clearRetained()
        case .ignore:
            break
        }
        return decision
    }

    private func clearRetained() async {
        await publisher.publish(topic: approvalTopic, payload: Data(), retained: true)
        await publisher.publish(topic: requestTopic, payload: Data(), retained: true)
    }
}
