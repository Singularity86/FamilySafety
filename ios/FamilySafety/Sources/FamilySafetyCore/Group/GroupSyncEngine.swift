import Foundation

/// Where the sync engine's outgoing messages go. `MqttTransport` satisfies it in the app;
/// tests capture the messages instead.
public protocol GroupSyncOutbox: Sendable {
    func send(topic: String, payload: Data, qos: Int, retained: Bool) async
}

/// What handling one inbound message did — for the UI's sync indicator and for tests.
public enum GroupSyncEvent: Equatable {
    case ignored(String)
    /// An authentic envelope the replay window refused (§3.1). Not a decrypt failure.
    case refused(ReplayWindow.Verdict)
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
    /// "Unknown key, ask for a refresh" is triggerable by anyone publishing junk kids (3.3, F16).
    private var unknownKeyRefresh = WindowRateLimiter(limit: 2, windowMs: 10 * 60_000)
    /// Catch-up when someone not in our roster speaks: at most once per 5 minutes (Android #49).
    private var unknownSenderRefresh = WindowRateLimiter(limit: 1, windowMs: 5 * 60_000)
    /// Unknown senders we've asked about, until a member's roster answers.
    private var askedAbout: Set<String> = []
    /// Unknown senders a member's roster answered without: not asked about again for a day.
    private var gaveUpOn: [String: Int64] = [:]
    static let catchUpGiveUpMs: Int64 = 24 * 60 * 60 * 1000
    /// Sequence numbers accepted in the last 10 minutes, per window key, with when. A second copy
    /// of one of these (LAN and broker both delivering, QoS 1 redelivery) is refused as usual
    /// but not logged: it's the network, not an attacker. Memory only.
    private var recentlyAccepted: [String: [Int64: Int64]] = [:]
    static let duplicateGraceMs: Int64 = 10 * 60 * 1000
    public private(set) var acks: [Int64: Set<String>] = [:]
    public private(set) var ledger: ProtocolLedger
    private let ledgerSlot: SecretSlot?
    /// Where refusals that may be an outsider at work are recorded (intrusion detection, phase 1).
    private let security: SecurityRecorder?
    /// Members whose envelopes were refused as a downgrade (§3.1); shown as "needs to update".
    public private(set) var downgraded: Set<String> = []

    /// - Parameter ledgerSlot: where the protocol ledger persists (3.3, 3.4); nil keeps it in memory.
    public init(
        identity: FamilySafeIdentity,
        store: GroupStateStore,
        outbox: GroupSyncOutbox,
        ledgerSlot: SecretSlot? = nil,
        security: SecurityRecorder? = nil,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.ledgerSlot = ledgerSlot
        self.security = security
        self.ledger = (try? ledgerSlot?.readJSON(ProtocolLedger.self)) ?? ProtocolLedger(observingSinceMs: now())
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
        let senderId: String, plaintext: String
        switch open(payload, roster: current) {
        case .opened(let sender, let body): senderId = sender.memberId; plaintext = body
        case .refused(let event):
            if event == .ignored("sender not in our roster"), let unknown = EncryptedEnvelope.peekSenderMemberId(json: payload) {
                await catchUpOnUnknownSender(unknown)
            }
            return event
        }
        guard let message = try? JSONDecoder().decode(GroupSyncMessage.self, from: Data(plaintext.utf8)) else {
            return .ignored("malformed group sync")
        }
        guard message.updaterMemberId == senderId else {
            return .ignored("envelope sender is not the updater")
        }
        guard message.updaterMemberId != identity.memberId else { return .ignored("our own update") }
        guard message.groupId == current.groupId else { return .ignored("different group") }
        guard verifySignature(message, roster: current) else {
            security?.record(.forgedSignature, memberId: senderId, detail: "roster update")
            return .ignored("bad update signature")
        }
        noteRosterAnswer(message.groupDefinition)

        if message.version < current.version {
            if staleRebroadcast.allow(senderId, now: now()) {
                await broadcast(current, changeType: .versionSync)
            }
            return .outcome(.stale(current))
        }
        if message.version == current.version &&
            message.groupDefinition.computeStateHash() == current.computeStateHash() {
            await sendAck(groupId: message.groupId, version: message.version, updaterMemberId: senderId)
            return .acknowledged(version: message.version)
        }
        return await apply(message, current: current)
    }

    /// An ack: sealed on `familysafe/{me}/group_ack` from a peer at protocol ≥ 4, or plaintext on
    /// `familysafe/group/{gid}/ack` from an older one (§6.4). Only ever used for display.
    public func handleAck(payload: Data) async {
        guard let current = await store.current,
              let ack = openControl(GroupUpdateAck.self, payload, roster: current, claimedSender: \.memberId),
              ack.memberId != identity.memberId, ack.groupId == current.groupId else { return }
        acks[ack.version, default: []].insert(ack.memberId)
        acks = acks.filter { $0.key >= current.version - 5 }
    }

    /// A message on `familysafe/{me}/sync_request`: sealed from a peer at protocol ≥ 4, else
    /// plaintext and unsigned (§6.4). Treated as a hint either way: answered only for current
    /// members, and rate-limited so a stranger can't turn it into a broadcast storm (F16).
    @discardableResult
    public func handleRefreshRequest(payload: Data) async -> GroupSyncEvent {
        guard let current = await store.current else { return .ignored("no group yet") }
        guard let request = openControl(GroupStateRefreshRequest.self, payload, roster: current, claimedSender: \.requesterMemberId) else {
            return .ignored("unauthenticated or malformed refresh request")
        }
        guard request.groupId == current.groupId else { return .ignored("different group") }
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

    // MARK: - Protocol ledger (3.3, 3.4)

    /// Records what a member's *authenticated* presence advertised (§6.2 rule 2).
    public func recordSighting(memberId: String, protocolVersion: Int) {
        let t = now()
        let previous = ledger.sightings[memberId]
        ledger.sightings[memberId] = PeerSighting(protocolVersion: protocolVersion, seenAtMs: t)
        // Persist on a change, and otherwise about hourly: the 30-day gate needs no finer clock.
        if previous?.protocolVersion != protocolVersion || t - (previous?.seenAtMs ?? 0) > 3_600_000 { saveLedger() }
    }

    /// Whether every member who counts can read a rotated key (3.3).
    public func rotationGateOpen(_ group: GroupDefinition) -> Bool {
        GroupKeys.everyoneAtLeast(memberIds: group.members.map(\.memberId), selfId: identity.memberId,
                                  sightings: ledger.sightings, observingSinceMs: ledger.observingSinceMs,
                                  nowMs: now(), required: GroupKeys.rotationProtocolVersion)
    }

    /// Whether every member who counts reads presence from the group topic (4.2).
    public func presenceGateOpen(_ group: GroupDefinition) -> Bool {
        GroupKeys.everyoneAtLeast(memberIds: group.members.map(\.memberId), selfId: identity.memberId,
                                  sightings: ledger.sightings, observingSinceMs: ledger.observingSinceMs,
                                  nowMs: now(), required: PresenceTopics.protocolVersion)
    }

    /// Someone not in our roster sent a roster update or presence: usually a member who joined
    /// while this phone was away, so ask for the current roster (Android #49). Not for ourselves
    /// or a removed member, and at most once per 5 minutes, since anyone can name an unknown ID.
    public func catchUpOnUnknownSender(_ memberId: String) async {
        let t = now()
        gaveUpOn = gaveUpOn.filter { t - $0.value < Self.catchUpGiveUpMs }
        guard memberId != identity.memberId, gaveUpOn[memberId] == nil, let current = await store.current,
              !current.containsMember(memberId),
              !current.removedMemberIds.contains(where: { Tombstones.memberId(of: $0) == memberId }),
              unknownSenderRefresh.allow("*", now: t) else { return }
        askedAbout.insert(memberId)
        await requestRefresh(reason: "unknown sender")
    }

    /// A member's authenticated roster arrived. Anyone we asked about who isn't in it is
    /// probably not a member at all (a stranger, a turned-down joiner): stop asking about them
    /// for a day. A real new member is in the roster that answers, so they're never given up on.
    private func noteRosterAnswer(_ roster: GroupDefinition) {
        guard !askedAbout.isEmpty else { return }
        let t = now()
        for id in askedAbout where !roster.containsMember(id) { gaveUpOn[id] = t }
        askedAbout = []
    }

    /// Someone outside the roster: a removed member is worth more attention than a stranger.
    nonisolated func recordStranger(_ memberId: String, roster: GroupDefinition, detail: String) {
        if roster.removedMemberIds.contains(where: { Tombstones.memberId(of: $0) == memberId }) {
            security?.record(.removedMemberActivity, memberId: memberId, detail: detail)
        } else if memberId != identity.memberId {
            security?.record(.unknownSender, memberId: memberId, detail: detail)
        }
    }

    /// Members to show as "needs to update" (§6.2 rule 3).
    public func outdatedMembers(_ group: GroupDefinition) -> Set<String> {
        ledger.outdated(group, downgraded: downgraded).subtracting([identity.memberId])
    }

    /// Presence sealed under a key we don't hold: ask for the current state, rate-limited.
    public func unknownGroupKeySeen(from memberId: String? = nil) async {
        security?.record(.unknownGroupKey, memberId: memberId)
        guard unknownKeyRefresh.allow("*", now: now()) else { return }
        await requestRefresh(reason: "unknown group key")
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
            guard let envelope = seal(plaintext, to: peer) else { continue }
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
        guard let json = try? JSONEncoder().encode(request) else { return }
        for peer in current.members where peer.memberId != identity.memberId {
            guard let payload = controlPayload(json, to: peer) else { continue }
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
            await sendAck(groupId: message.groupId, version: message.version, updaterMemberId: message.updaterMemberId)
            // We merged their branch with ours: send the result back so both sides converge.
            if resolved.computeStateHash() != message.groupDefinition.computeStateHash() {
                await broadcast(resolved, changeType: .fullSync)
            }
            if message.changeType == .memberAdded {
                await requestRefresh(reason: "member_added", changedMemberId: message.changedMemberId)
            }
        case .keptOurs(let ours):
            // Android acks here too, then sends its winning branch back.
            await sendAck(groupId: message.groupId, version: message.version, updaterMemberId: message.updaterMemberId)
            await broadcast(ours, changeType: .fullSync)
        case .refusedMissingSelf:
            await requestRefresh(reason: "missing_self")
        case .rejected(let reason):
            security?.record(.rosterUpdateRejected, memberId: message.updaterMemberId, detail: "\(reason)")
        case .stale, .removed, .storageFailed:
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

    /// Only the updater waits on acks: one at protocol ≥ 4 gets it sealed on their own inbox;
    /// an older one still listens on the group-wide topic (3.4).
    private func sendAck(groupId: String, version: Int64, updaterMemberId: String) async {
        let ack = GroupUpdateAck(groupId: groupId, version: version, memberId: identity.memberId, timestamp: now())
        guard let json = try? JSONEncoder().encode(ack) else { return }
        if let updater = await store.current?.findMember(byId: updaterMemberId), ledger.speaksSequenced(updater) {
            guard let sealed = controlPayload(json, to: updater) else { return }
            await outbox.send(topic: Topics.groupAckInbox(memberId: updater.memberId), payload: sealed, qos: 1, retained: false)
        } else {
            await outbox.send(topic: Topics.groupAck(groupId: groupId), payload: json, qos: 1, retained: false)
        }
    }

    // MARK: - Envelopes (§3, §3.1)

    private enum Opened {
        case opened(sender: FamilyMember, body: String)
        case refused(GroupSyncEvent)
    }

    /// Encrypts `plaintext` to `peer`, as a sequenced body when they can read one. The counter is
    /// saved before the number is used, so a crash can't reuse it.
    private func seal(_ plaintext: String, to peer: FamilyMember) -> Data? {
        guard let key = Hex.decode(peer.x25519PublicKey) else { return nil }
        var body = plaintext
        if ledger.speaksSequenced(peer) {
            let t = now()
            let seq = ledger.counter.next(nowMs: t)
            guard saveLedger(),
                  let json = try? JSONEncoder().encode(SequencedBody(seq: seq, ts: t, body: plaintext)),
                  let wrapped = String(data: json, encoding: .utf8) else { return nil }
            body = wrapped
        }
        return try? crypto.encrypt(plaintext: body, recipientMemberId: peer.memberId, recipientX25519PublicKey: key)
    }

    /// Verifies and opens an envelope from a roster member, then runs the replay window.
    private func open(_ payload: Data, roster: GroupDefinition) -> Opened {
        guard let senderId = EncryptedEnvelope.peekSenderMemberId(json: payload) else {
            return .refused(.ignored("no sender in envelope"))
        }
        // Keys come from OUR roster, never from the envelope.
        guard let sender = roster.findMember(byId: senderId),
              let senderX = Hex.decode(sender.x25519PublicKey),
              let senderEd = Hex.decode(sender.ed25519PublicKey) else {
            recordStranger(senderId, roster: roster, detail: "encrypted message")
            return .refused(.ignored("sender not in our roster"))
        }
        let plaintext: String
        do {
            plaintext = try crypto.decrypt(envelopeJSON: payload, senderMemberId: senderId,
                                           senderX25519PublicKey: senderX, senderEd25519PublicKey: senderEd)
        } catch E2EEError.invalidSignature {
            security?.record(.forgedSignature, memberId: senderId, detail: "envelope")
            return .refused(.ignored("undecryptable"))
        } catch {
            return .refused(.ignored("undecryptable"))
        }
        let sequenced = SequencedBody.parse(plaintext)
        let key = ProtocolLedger.windowKey(sender), t = now()
        let verdict = ledger.window.check(sender: key, body: sequenced, nowMs: t)
        recentlyAccepted = recentlyAccepted.compactMapValues { seqs in
            let kept = seqs.filter { t - $0.value < Self.duplicateGraceMs }
            return kept.isEmpty ? nil : kept
        }
        guard verdict == .accept else {
            if verdict == .unsequencedAfterSequenced {
                downgraded.insert(senderId)
                security?.record(.downgrade, memberId: senderId)
            } else if !(verdict == .replay && sequenced.map { recentlyAccepted[key]?[$0.seq] != nil } == true) {
                security?.record(.replayRefused, memberId: senderId, detail: "\(verdict)")
            }
            return .refused(.refused(verdict))
        }
        if let sequenced {
            recentlyAccepted[key, default: [:]][sequenced.seq] = t
            downgraded.remove(senderId)
            saveLedger()
            return .opened(sender: sender, body: sequenced.body)
        }
        return .opened(sender: sender, body: plaintext)
    }

    /// The payload for a refresh request or ack to `peer`: sealed at protocol ≥ 4, else bare.
    private func controlPayload(_ json: Data, to peer: FamilyMember) -> Data? {
        guard ledger.speaksSequenced(peer) else { return json }
        guard let plaintext = String(data: json, encoding: .utf8) else { return nil }
        return seal(plaintext, to: peer)
    }

    /// A refresh request or ack from a roster member, or nil. Sealed, the member it names must
    /// be the envelope's sender. Plaintext proves nothing, so it's taken only while the member it
    /// names has never sent us a sequenced body; once they have, it's a forgery or a replay.
    private func openControl<T: Decodable>(_ type: T.Type, _ payload: Data, roster: GroupDefinition,
                                           claimedSender: (T) -> String) -> T? {
        if EncryptedEnvelope.peekSenderMemberId(json: payload) != nil {
            guard case .opened(let sender, let body) = open(payload, roster: roster),
                  let message = try? JSONDecoder().decode(T.self, from: Data(body.utf8)),
                  claimedSender(message) == sender.memberId else { return nil }
            return message
        }
        guard let message = try? JSONDecoder().decode(T.self, from: payload),
              let named = roster.findMember(byId: claimedSender(message)) else { return nil }
        guard !ledger.window.hasSequenced(ProtocolLedger.windowKey(named)) else {
            security?.record(.forgedControlMessage, memberId: named.memberId, detail: "\(T.self)")
            return nil
        }
        return message
    }

    @discardableResult
    private func saveLedger() -> Bool {
        guard let ledgerSlot else { return true }
        return (try? ledgerSlot.writeJSON(ledger)) != nil
    }
}
