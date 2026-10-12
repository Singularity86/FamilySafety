import Foundation

/// Where the sync engine's outgoing messages go. `MqttTransport` satisfies it in the app;
/// tests capture the messages instead.
public protocol GroupSyncOutbox: Sendable {
    func send(topic: String, payload: Data, qos: Int, retained: Bool) async
}

/// What handling one inbound message did — for the UI's sync indicator and for tests.
public enum GroupSyncEvent: Equatable {
    case ignored(String)
    case acknowledged(version: Int64)
    case outcome(GroupApplyOutcome)
    case answeredRefresh(requester: String)
    case refreshRateLimited(requester: String)
}

/// Fixed-window limiter with an injected clock, so tests are deterministic.
struct WindowRateLimiter {
    let limit: Int
    let windowMs: Int64
    private var hits: [String: [Int64]] = [:]

    init(limit: Int, windowMs: Int64) {
        self.limit = limit
        self.windowMs = windowMs
    }

    mutating func allow(_ key: String, now: Int64) -> Bool {
        var recent = (hits[key] ?? []).filter { now - $0 < windowMs }
        guard recent.count < limit else {
            hits[key] = recent
            return false
        }
        recent.append(now)
        hits[key] = recent
        return true
    }
}

/// Group-state replication over the broker (§6.4, §7.4). Ports the receive, apply, ack,
/// rebroadcast and refresh paths of GroupSyncManager.kt as of Android 1.15.3, wire-compatible,
/// with the review's iOS-side rules built in:
///
/// - The envelope's authenticated sender must be the update's `updaterMemberId` (F25, part):
///   no member can re-wrap another member's signed update and alter its unsigned fields.
///   Android always sends with updater == itself, so this costs nothing in interop.
/// - Refresh requests are answered only for current members, and rate-limited per requester
///   and overall (F16). Stale-version replays are rate-limited the same way.
/// - No refresh request is sent because a decrypt failed (F16).
/// - Acks are recorded, never waited on (E5). Being an actor, inbound messages are handled
///   one at a time, in arrival order.
public actor GroupSyncEngine {
    private let identity: FamilySafeIdentity
    private let crypto: E2EECrypto
    private let store: GroupStateStore
    private let outbox: GroupSyncOutbox
    private let now: @Sendable () -> Int64

    private var refreshPerRequester = WindowRateLimiter(limit: 2, windowMs: 60_000)
    private var refreshOverall = WindowRateLimiter(limit: 6, windowMs: 60_000)
    private var staleRebroadcast = WindowRateLimiter(limit: 1, windowMs: 60_000)
    private var outgoingRefresh = WindowRateLimiter(limit: 2, windowMs: 60_000)
    public private(set) var acks: [Int64: Set<String>] = [:]

    public init(
        identity: FamilySafeIdentity,
        store: GroupStateStore,
        outbox: GroupSyncOutbox,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.identity = identity
        self.crypto = E2EECrypto(
            localMemberId: identity.memberId,
            localEd25519SecretKey: identity.ed25519SecretKey64,
            localX25519SecretKey: identity.x25519PrivateKey
        )
        self.store = store
        self.outbox = outbox
        self.now = now
    }

    // MARK: - Inbound

    /// A message on `familysafe/{me}/group_sync`.
    @discardableResult
    public func handleGroupSync(payload: Data) async -> GroupSyncEvent {
        guard let current = await store.current else { return .ignored("no group yet") }
        guard let senderId = EncryptedEnvelope.peekSenderMemberId(json: payload) else {
            return .ignored("no sender in envelope")
        }
        guard let sender = current.findMember(byId: senderId),
              let senderX = Hex.decode(sender.x25519PublicKey),
              let senderEd = Hex.decode(sender.ed25519PublicKey) else {
            return .ignored("sender not in our roster")
        }
        guard let plaintext = try? crypto.decrypt(
            envelopeJSON: payload, senderMemberId: senderId,
            senderX25519PublicKey: senderX, senderEd25519PublicKey: senderEd
        ), let message = try? JSONDecoder().decode(GroupSyncMessage.self, from: Data(plaintext.utf8)) else {
            return .ignored("undecryptable or malformed")
        }
        guard message.updaterMemberId == senderId else {
            return .ignored("envelope sender is not the updater")
        }
        guard message.updaterMemberId != identity.memberId else { return .ignored("our own update") }
        guard message.groupId == current.groupId else { return .ignored("different group") }
        guard verifySignature(message, roster: current) else { return .ignored("bad update signature") }

        if message.version < current.version {
            if staleRebroadcast.allow(senderId, now: now()) {
                await broadcast(current, changeType: .versionSync)
            }
            return .outcome(.stale(current))
        }
        if message.version == current.version &&
            message.groupDefinition.computeStateHash() == current.computeStateHash() {
            await sendAck(groupId: message.groupId, version: message.version)
            return .acknowledged(version: message.version)
        }
        return await apply(message, current: current)
    }

    /// A message on `familysafe/group/{gid}/ack`. Plaintext on the wire (Android parity), so
    /// only ever used for display — never for a decision.
    public func handleAck(payload: Data) async {
        guard let ack = try? JSONDecoder().decode(GroupUpdateAck.self, from: payload),
              ack.memberId != identity.memberId,
              let current = await store.current, ack.groupId == current.groupId,
              current.containsMember(ack.memberId) else { return }
        acks[ack.version, default: []].insert(ack.memberId)
        acks = acks.filter { $0.key >= current.version - 5 }
    }

    /// A message on `familysafe/{me}/sync_request`. Plaintext and unsigned on the wire
    /// (Android parity), so it is treated as a hint: answered only for current members, and
    /// rate-limited so a stranger can't turn it into a broadcast storm (F16).
    @discardableResult
    public func handleRefreshRequest(payload: Data) async -> GroupSyncEvent {
        guard let request = try? JSONDecoder().decode(GroupStateRefreshRequest.self, from: payload) else {
            return .ignored("malformed refresh request")
        }
        guard let current = await store.current, request.groupId == current.groupId else {
            return .ignored("different group")
        }
        guard request.requesterMemberId != identity.memberId else { return .ignored("our own request") }
        guard current.containsMember(request.requesterMemberId) else { return .ignored("requester not a member") }
        guard current.version >= request.minimumVersion else { return .ignored("we are behind too") }
        let t = now()
        guard refreshPerRequester.allow(request.requesterMemberId, now: t), refreshOverall.allow("*", now: t) else {
            return .refreshRateLimited(requester: request.requesterMemberId)
        }
        await broadcast(current, changeType: .fullSync, changedMemberId: request.changedMemberId)
        return .answeredRefresh(requester: request.requesterMemberId)
    }

    // MARK: - Outbound

    /// Signs `group` as this device's update and sends it, encrypted, to every other member.
    public func broadcast(
        _ group: GroupDefinition,
        changeType: GroupChangeType,
        changedMemberId: String? = nil,
        quorumSignatures: [QuorumSignatureEntry] = []
    ) async {
        let timestamp = now()
        let signed = Array(GroupSignaturePayloads.syncSignature(
            groupId: group.groupId, version: group.version, updaterMemberId: identity.memberId,
            timestamp: timestamp, groupDefinition: group
        ).utf8)
        guard let signature = try? SodiumRaw.signDetached(message: signed, secretKey: identity.ed25519SecretKey64) else { return }
        let message = GroupSyncMessage(
            groupId: group.groupId, version: group.version, groupDefinition: group,
            updaterMemberId: identity.memberId, changeType: changeType, changedMemberId: changedMemberId,
            timestamp: timestamp, signature: Hex.encode(signature), quorumSignatures: quorumSignatures
        )
        guard let json = try? JSONEncoder().encode(message), let plaintext = String(data: json, encoding: .utf8) else { return }
        for peer in group.members where peer.memberId != identity.memberId {
            guard let key = Hex.decode(peer.x25519PublicKey),
                  let envelope = try? crypto.encrypt(plaintext: plaintext, recipientMemberId: peer.memberId, recipientX25519PublicKey: key) else { continue }
            await outbox.send(topic: Topics.groupSyncInbox(memberId: peer.memberId), payload: envelope, qos: 1, retained: false)
        }
    }

    /// Asks every peer to send its state. Rate-limited on our side too.
    public func requestRefresh(reason: String, changedMemberId: String? = nil) async {
        guard let current = await store.current, outgoingRefresh.allow("*", now: now()) else { return }
        let request = GroupStateRefreshRequest(
            groupId: current.groupId, requesterMemberId: identity.memberId,
            minimumVersion: current.version, changedMemberId: changedMemberId, reason: reason, timestamp: now()
        )
        guard let payload = try? JSONEncoder().encode(request) else { return }
        for peer in current.members where peer.memberId != identity.memberId {
            await outbox.send(topic: Topics.syncRequest(memberId: peer.memberId), payload: payload, qos: 1, retained: false)
        }
    }

    // MARK: - Internals

    private func apply(_ message: GroupSyncMessage, current: GroupDefinition) async -> GroupSyncEvent {
        var voters = Set<String>()
        if !message.quorumSignatures.isEmpty, let target = message.changedMemberId {
            voters = GroupTransitionValidator.verifiedQuorumVoters(
                current: current, targetMemberId: target, proposedVersion: message.version,
                votes: message.quorumSignatures.map { QuorumVote(voterMemberId: $0.voterMemberId, signatureHex: $0.signature) }
            )
        }
        let authorizers = GroupTransitionValidator.verifiedRemovalAuthorizers(current: current, remote: message.groupDefinition)
        let outcome = await store.apply(
            remote: message.groupDefinition, updaterMemberId: message.updaterMemberId,
            verifiedQuorumVoterIds: voters, verifiedRemovalAuthorizers: authorizers
        )

        switch outcome {
        case .applied(let resolved):
            await sendAck(groupId: message.groupId, version: message.version)
            // We merged their branch with ours: send the result back so both sides converge.
            if resolved.computeStateHash() != message.groupDefinition.computeStateHash() {
                await broadcast(resolved, changeType: .fullSync)
            }
            if message.changeType == .memberAdded {
                await requestRefresh(reason: "member_added", changedMemberId: message.changedMemberId)
            }
        case .keptOurs(let ours):
            // Android acks here too, then sends its winning branch back.
            await sendAck(groupId: message.groupId, version: message.version)
            await broadcast(ours, changeType: .fullSync)
        case .refusedMissingSelf:
            await requestRefresh(reason: "missing_self")
        case .stale, .rejected, .removed, .storageFailed:
            break
        }
        return .outcome(outcome)
    }

    private func verifySignature(_ message: GroupSyncMessage, roster: GroupDefinition) -> Bool {
        // The updater's key comes from OUR roster — never from the definition being proposed.
        guard let updater = roster.findMember(byId: message.updaterMemberId),
              let key = Hex.decode(updater.ed25519PublicKey),
              let signature = Hex.decode(message.signature) else { return false }
        let payload = Array(GroupSignaturePayloads.syncSignature(
            groupId: message.groupId, version: message.version, updaterMemberId: message.updaterMemberId,
            timestamp: message.timestamp, groupDefinition: message.groupDefinition
        ).utf8)
        return (try? SodiumRaw.ensureInitialized()) != nil &&
            SodiumRaw.signVerifyDetached(signature: signature, message: payload, publicKey: key)
    }

    private func sendAck(groupId: String, version: Int64) async {
        let ack = GroupUpdateAck(groupId: groupId, version: version, memberId: identity.memberId, timestamp: now())
        guard let payload = try? JSONEncoder().encode(ack) else { return }
        await outbox.send(topic: Topics.groupAck(groupId: groupId), payload: payload, qos: 1, retained: false)
    }
}
