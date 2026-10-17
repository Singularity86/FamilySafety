import Foundation

/// A verified join request waiting for this phone's owner to approve or turn down.
public struct JoinPrompt: Equatable, Identifiable, Sendable {
    public var id: String { requestId }
    public let requestId: String
    public let displayName: String
    public let requesterId: String
    /// Six digits; the joiner's pending screen shows the same ones (§8.3).
    public let fingerprint: String
    let request: JoinRequest
}

/// What handling one message on our `join_request` topic did.
public enum JoinDeskEvent: Equatable, Sendable {
    case ignored(String)
    /// Silently dropped: failed a §8.4 check. Never shown to anyone.
    case dropped(String)
    case prompted(JoinPrompt)
    /// A member's own phone restored from its phrase: approved again with no prompt (§8.4, 1.15.1).
    case autoApproved(memberId: String)
}

public enum JoinDeskError: Error, Equatable {
    case noFamily
    case unknownRequest
    /// The requester now names a member whose keys differ (§8.4 step 1, re-checked on approval).
    case keyConflict
    case storage
}

/// The inviter's side of joining (§8.2, §8.4): hands out one-time v2 invites, verifies requests,
/// and approves or rejects them. v1 requests are never accepted (spec: iOS ships after Android's
/// grace period).
public actor InviterDesk {
    private let identity: FamilySafeIdentity
    private let crypto: E2EECrypto
    private let store: GroupStateStore
    private let engine: GroupSyncEngine
    private let outbox: GroupSyncOutbox
    private let bookSlot: SecretSlot
    private let now: @Sendable () -> Int64
    private var book: InviteBook
    public private(set) var prompts: [JoinPrompt] = []

    public init(identity: FamilySafeIdentity, store: GroupStateStore, engine: GroupSyncEngine, outbox: GroupSyncOutbox,
                bookSlot: SecretSlot, now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) throws {
        self.identity = identity
        self.crypto = E2EECrypto(localMemberId: identity.memberId, localEd25519SecretKey: identity.ed25519SecretKey64,
                                 localX25519SecretKey: identity.x25519PrivateKey)
        self.store = store
        self.engine = engine
        self.outbox = outbox
        self.bookSlot = bookSlot
        self.now = now
        self.book = try bookSlot.readJSON(InviteBook.self) ?? InviteBook()
    }

    /// A fresh invite for the current family. Saved before it's returned, so the QR never shows
    /// a secret this phone could forget.
    public func issueInvite(inviterName: String) async throws -> InviteCode {
        guard let group = await store.current else { throw JoinDeskError.noFamily }
        var next = book
        let invite = try next.issue(group: group, inviter: identity, inviterName: inviterName, now: now())
        do { try bookSlot.writeJSON(next) } catch { throw JoinDeskError.storage }
        book = next
        return invite
    }

    public func handle(payload: Data) async -> JoinDeskEvent {
        guard !payload.isEmpty else { return .ignored("retained clear") }
        guard let message = try? JSONDecoder().decode(JoinRequestV2Message.self, from: payload) else {
            return .dropped("not a v2 request")
        }
        let request: JoinRequest
        switch JoinV2Verifier.verify(message: message, now: now(), invites: book, inviterCrypto: crypto) {
        case .rejected(let reason): return .dropped(reason)
        case .verified(let r): request = r
        }
        guard !book.isClosed(request.requestId) else { return .ignored("already decided") }
        guard let group = await store.current, group.groupId == request.groupId else { return .dropped("not for this family") }

        if let existing = group.findMember(byId: request.requesterId) {
            // RejoinPolicy: same keys and a valid signature (v2 always has one) = this member's own
            // phone restored from its phrase. Different keys = someone else using their name.
            guard Self.keysMatch(existing, request) else { return .dropped("member ID with different keys") }
            do { try await approve(request, inviteId: message.inviteId) } catch { return .ignored("could not approve yet") }
            return .autoApproved(memberId: request.requesterId)
        }
        if let known = prompts.first(where: { $0.requestId == request.requestId }) { return .prompted(known) }
        guard let ed = Data(base64Encoded: request.ed25519PublicKey), let x = Data(base64Encoded: request.x25519PublicKey) else {
            return .dropped("bad keys")
        }
        let prompt = JoinPrompt(requestId: request.requestId, displayName: request.displayName, requesterId: request.requesterId,
                                fingerprint: JoinV2.fingerprint(ed25519: Array(ed), x25519: Array(x)), request: request)
        prompts.append(prompt)
        return .prompted(prompt)
    }

    public func approve(requestId: String) async throws {
        guard let prompt = prompts.first(where: { $0.requestId == requestId }) else { throw JoinDeskError.unknownRequest }
        guard let inviteId = prompt.request.inviteId else { throw JoinDeskError.unknownRequest }
        try await approve(prompt.request, inviteId: inviteId)
    }

    public func reject(requestId: String) async throws {
        guard let prompt = prompts.first(where: { $0.requestId == requestId }) else { throw JoinDeskError.unknownRequest }
        let request = prompt.request
        guard let x = Data(base64Encoded: request.x25519PublicKey) else { throw JoinDeskError.unknownRequest }
        let body = JoinRejectionPayload(requestId: request.requestId, joinerMemberId: request.requesterId, timestamp: now())
        let envelope = try crypto.encrypt(plaintext: String(decoding: try JSONEncoder().encode(body), as: UTF8.self),
                                          recipientMemberId: request.requesterId, recipientX25519PublicKey: Array(x))
        let message = JoinRejectionMessage(inviterEd25519PublicKey: identity.ed25519PublicKeyHex,
                                           inviterX25519PublicKey: identity.x25519PublicKeyHex,
                                           encryptedRejection: String(decoding: envelope, as: UTF8.self))
        await outbox.send(topic: Topics.joinApproval(joinerMemberId: request.requesterId),
                          payload: try JSONEncoder().encode(message), qos: 1, retained: true)
        try close(request, inviteId: request.inviteId)
    }

    // MARK: - Internals

    private func approve(_ request: JoinRequest, inviteId: String?) async throws {
        guard let ed = Data(base64Encoded: request.ed25519PublicKey), let x = Data(base64Encoded: request.x25519PublicKey) else {
            throw JoinDeskError.unknownRequest
        }
        guard let before = await store.current else { throw JoinDeskError.noFamily }
        if let existing = before.findMember(byId: request.requesterId), !Self.keysMatch(existing, request) {
            prompts.removeAll { $0.requestId == request.requestId }
            throw JoinDeskError.keyConflict
        }
        let joiner = FamilyMember(memberId: request.requesterId, displayName: request.displayName,
                                  ed25519PublicKey: Hex.encode(Array(ed)), x25519PublicKey: Hex.encode(Array(x)),
                                  addedAtEpochMs: now())
        var added = false
        let group: GroupDefinition
        do {
            group = try await store.addMember(joiner, inviterMemberId: identity.memberId, now: now())
            added = true
        } catch LocalEditError.alreadyMember {
            group = before
        } catch {
            throw JoinDeskError.storage
        }
        let json = String(decoding: try JSONEncoder().encode(group), as: UTF8.self)
        let envelope = try crypto.encrypt(plaintext: json, recipientMemberId: request.requesterId, recipientX25519PublicKey: Array(x))
        let approval = JoinApprovalMessage(inviterEd25519PublicKey: identity.ed25519PublicKeyHex,
                                           inviterX25519PublicKey: identity.x25519PublicKeyHex,
                                           encryptedGroupDefinition: String(decoding: envelope, as: UTF8.self))
        await outbox.send(topic: Topics.joinApproval(joinerMemberId: request.requesterId),
                          payload: try JSONEncoder().encode(approval), qos: 1, retained: true)
        try close(request, inviteId: inviteId)
        if added {
            await engine.broadcast(group, changeType: .memberAdded, changedMemberId: request.requesterId)
            await engine.requestRefresh(reason: "member added", changedMemberId: request.requesterId)
        }
    }

    /// Clears the retained request, uses up the invite and remembers the decision.
    private func close(_ request: JoinRequest, inviteId: String?) throws {
        prompts.removeAll { $0.requestId == request.requestId }
        var next = book
        next.close(request.requestId)
        if let inviteId { next.markUsed(inviteId) }
        next.prune(now: now())
        try? bookSlot.writeJSON(next)
        book = next
        let outbox = outbox, topic = Topics.joinRequest(inviterMemberId: identity.memberId)
        Task { await outbox.send(topic: topic, payload: Data(), qos: 1, retained: true) }
    }

    private static func keysMatch(_ member: FamilyMember, _ request: JoinRequest) -> Bool {
        guard let ed = Data(base64Encoded: request.ed25519PublicKey), let x = Data(base64Encoded: request.x25519PublicKey) else { return false }
        return member.ed25519PublicKey.lowercased() == Hex.encode(Array(ed)) && member.x25519PublicKey.lowercased() == Hex.encode(Array(x))
    }
}
