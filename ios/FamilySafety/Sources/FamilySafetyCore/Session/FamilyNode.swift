import CocoaMQTT
import Foundation

/// Where this phone stands with a family.
public enum NodeState: Equatable, Sendable {
    case noFamily
    /// Waiting for an inviter's answer. `fingerprint` is shown so both people can compare it.
    case joining(familyName: String, inviterName: String, fingerprint: String)
    case member(GroupDefinition)
    case rejected
    /// An authorized removal ended this phone's membership.
    case removed
    /// Stored data couldn't be read. Nothing was deleted; retry later (F14).
    case storageUnavailable
}

public enum NodeEvent: Sendable {
    case state(NodeState)
    case connection(MqttConnectionState)
    case prompts([JoinPrompt])
    case presence([String: PresenceStatus])
    /// Someone's identity (or ours) is signed in on two phones at once (2.2). Show it plainly.
    case identityAlert(String)
    /// Members whose app is too old to follow the family, or who went back to an older one
    /// (§6.2 rule 3). Name them in the UI.
    case needsUpdate(Set<String>)
    /// The security log changed (intrusion detection, phase 1).
    case securityLog(SecurityLog)
    /// The newest known location per member, ours included (B2).
    case locations([String: LocationUpdate])
    /// Whether "Share my location" is off.
    case sharingPaused(Bool)
}

public enum NodeError: Error, Equatable {
    case invalidCode
    case alreadyInFamily
    case notInFamily
    case storageUnavailable
}

/// The running app's connection to its family: one MQTT session, every inbound message routed
/// in arrival order (E5), and the family's sync, joining and presence on top. The UI talks to
/// it and listens to `NodeEvent`s; it never touches the transport directly.
public actor FamilyNode {
    private let profile: LocalProfile
    private let identity: FamilySafeIdentity
    private let credentials: BrokerCredentials
    private let store: GroupStateStore
    private let pendingJoinSlot: SecretSlot
    private let transport: MqttTransport
    private let outbox: TransportOutbox
    private let engine: GroupSyncEngine
    private let desk: InviterDesk
    private let onEvent: @Sendable (NodeEvent) -> Void
    private let now: @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }

    private var coordinator: JoinCoordinator?
    private var presence: [String: PresenceStatus] = [:]
    private let deviceId: String
    private var cloneDetector = CloneDetector()
    private var lastSelfCloneAlert: Int64?
    private var running = false
    private var backoffSeconds: UInt64 = 2
    private var connecting = false
    /// A reconnect asked for while a connect was in flight; done as soon as it finishes.
    private var reconnectRequested = false
    /// Which group key sealed the last-will at connect; a rotation needs a reconnect to re-seal it.
    private var willKid: String?
    /// Whether this connection uses the group presence topic only (4.2); fixed at connect.
    private var presenceSwitched = false
    /// The timestamp this connection's will was signed with, sent as `sessionStart` (§6.2).
    private var sessionStart: Int64?
    private var presenceBook: PresenceBook
    private let presenceSlot: SecretSlot
    private let security: SecurityRecorder
    private var location: LocationState
    private let locationSlot: SecretSlot
    private var heartbeat: Task<Void, Never>?
    /// Woken in the background for one fix: connect, send it, disconnect (spec §11). No
    /// heartbeat re-stamp on that connect, or it would make the new fix look redundant.
    private var backgroundWake = false
    /// Android re-stamps and re-sends the last fix every 5 minutes so freshness itself is news.
    static let locationHeartbeatSeconds: UInt64 = 5 * 60
    private var lastNeedsUpdate: Set<String> = []
    private var inbound: AsyncStream<(String, Data, Bool)>.Continuation?
    private var bridge: TransportBridge?
    public private(set) var state: NodeState = .noFamily

    public init(profile: LocalProfile, credentials: BrokerCredentials, groupPersistence: GroupStatePersistence,
                inviteSlot: SecretSlot, pendingJoinSlot: SecretSlot, deviceSlot: SecretSlot, ledgerSlot: SecretSlot,
                presenceSlot: SecretSlot, securitySlot: SecretSlot, locationSlot: SecretSlot,
                onEvent: @escaping @Sendable (NodeEvent) -> Void) throws {
        self.locationSlot = locationSlot
        self.location = (try? locationSlot.readJSON(LocationState.self)) ?? LocationState()
        let security = SecurityRecorder(slot: securitySlot)
        security.onChange = { onEvent(.securityLog($0)) }
        self.security = security
        self.presenceSlot = presenceSlot
        let book = (try? presenceSlot.readJSON(PresenceBook.self)) ?? PresenceBook()
        self.presenceBook = book
        self.presence = book.statuses
        self.profile = profile
        self.identity = try profile.identity()
        self.credentials = credentials
        self.store = GroupStateStore(localMemberId: identity.memberId, persistence: groupPersistence)
        self.pendingJoinSlot = pendingJoinSlot
        // One per install, never backed up or transferred (Keychain …ThisDeviceOnly).
        if let saved = try deviceSlot.read().flatMap({ String(data: $0, encoding: .utf8) }), saved.count == 32 {
            deviceId = saved
        } else {
            try SodiumRaw.ensureInitialized()
            let fresh = Hex.encode(SodiumRaw.randomBytes(16))
            try deviceSlot.write(Data(fresh.utf8))
            deviceId = fresh
        }
        self.transport = MqttTransport(memberId: identity.memberId, credentials: credentials, deviceId: deviceId)
        self.outbox = TransportOutbox(transport: transport)
        self.engine = GroupSyncEngine(identity: identity, store: store, outbox: outbox, ledgerSlot: ledgerSlot, security: security)
        self.desk = try InviterDesk(identity: identity, store: store, engine: engine, outbox: outbox, bookSlot: inviteSlot)
        self.onEvent = onEvent
    }

    public var memberId: String { identity.memberId }

    // MARK: - Lifecycle

    /// Loads what's saved and connects. Call when the app comes to the foreground.
    public func start() async {
        guard !running else { return }
        running = true
        startInbound()
        do {
            if let group = try await store.load() {
                setState(.member(group))
                // The last known presence, until the broker says otherwise (no retained replay
                // when it kept our session, §4).
                if !presence.isEmpty { onEvent(.presence(presence)) }
                onEvent(.sharingPaused(location.paused))
                if !location.book.latest.isEmpty { onEvent(.locations(location.book.latest)) }
            } else if let pending = try pendingJoinSlot.readJSON(PendingJoin.self) {
                resumeJoin(pending)
            } else {
                setState(.noFamily)
            }
        } catch {
            setState(.storageUnavailable)
            running = false
            return
        }
        await connect()
    }

    /// Disconnects cleanly (peers see "offline" at once). Call when the app goes to the background.
    public func stop() async {
        running = false
        heartbeat?.cancel()
        heartbeat = nil
        await coordinator?.stop()
        if transport.isConnected, let group = await store.current {
            publishPresence(isOnline: false, group: group)
            try? await Task.sleep(nanoseconds: 300_000_000)
        }
        transport.disconnect()
    }

    // MARK: - Family actions

    public func createFamily(name: String) async throws {
        guard case .noFamily = state else { throw NodeError.alreadyInFamily }
        let me = FamilyMember(memberId: identity.memberId, displayName: profile.displayName,
                              ed25519PublicKey: identity.ed25519PublicKeyHex, x25519PublicKey: identity.x25519PublicKeyHex,
                              addedAtEpochMs: now())
        let group: GroupDefinition
        do { group = try await store.create(groupName: name, creator: me, now: now()) } catch LocalEditError.alreadyInFamily {
            throw NodeError.alreadyInFamily
        } catch { throw NodeError.storageUnavailable }
        setState(.member(group))
        await reconnect()
    }

    /// Starts joining with a scanned or pasted invite code.
    public func join(code: String) async throws {
        switch state {
        case .noFamily, .rejected: break
        default: throw NodeError.alreadyInFamily
        }
        guard let invite = InviteCode.decode(base64: code) else { throw NodeError.invalidCode }
        let pending = try JoinFlow.prepare(identity: identity, displayName: profile.displayName, invite: invite)
        do { try pendingJoinSlot.writeJSON(pending) } catch { throw NodeError.storageUnavailable }
        resumeJoin(pending)
        if transport.isConnected { await coordinator?.start() } else { await connect() }
    }

    public func cancelJoin() async {
        await coordinator?.stop()
        coordinator = nil
        try? pendingJoinSlot.delete()
        setState(.noFamily)
    }

    public func issueInvite() async throws -> String {
        guard case .member = state else { throw NodeError.notInFamily }
        return try await desk.issueInvite(inviterName: profile.displayName).encodedBase64()
    }

    /// Renames this phone's person and/or sets their colour; signed (3.1) and sent to the family.
    public func updateProfile(displayName: String? = nil, colorHue: Float?? = .none) async throws {
        guard case .member = state else { throw NodeError.notInFamily }
        let updated: GroupDefinition?
        do { updated = try await store.updateMyProfile(identity: identity, displayName: displayName, colorHue: colorHue, now: now()) } catch {
            throw NodeError.storageUnavailable
        }
        guard let group = updated else { return }
        setState(.member(group))
        await engine.broadcast(group, changeType: .nameChanged, changedMemberId: identity.memberId)
    }

    /// Leaves the family: a self-tombstone with a signed proof goes to everyone else, then this
    /// phone forgets the family. Offline is announced first, while the will can still be sealed.
    public func leave() async throws {
        guard case .member = state else { throw NodeError.notInFamily }
        let before = await store.current
        let group: GroupDefinition
        do { group = try await store.remove(memberId: identity.memberId, identity: identity) } catch { throw NodeError.storageUnavailable }
        await engine.broadcast(group, changeType: .memberRemoved, changedMemberId: identity.memberId)
        if let before { publishPresence(isOnline: false, group: before) }
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        transport.clearRetained(topic: Topics.presence(memberId: identity.memberId))
        if let before { transport.clearRetained(topic: PresenceTopics.group(groupId: before.groupId, memberId: identity.memberId)) }
        try? await store.forget()
        presence = [:]
        presenceBook = PresenceBook()
        try? presenceSlot.delete()
        // Keep only the pause choice: it's this person's, not the family's.
        let paused = location.paused
        location = LocationState()
        location.paused = paused
        saveLocation()
        onEvent(.locations([:]))
        onEvent(.presence(presence))
        setState(.noFamily)
        await reconnect()
    }

    /// The creator removes someone else.
    public func removeMember(_ memberId: String) async throws {
        guard case .member = state, memberId != identity.memberId else { throw NodeError.notInFamily }
        let group: GroupDefinition
        do { group = try await store.remove(memberId: memberId, identity: identity) } catch { throw NodeError.notInFamily }
        setState(.member(group))
        await engine.broadcast(group, changeType: .memberRemoved, changedMemberId: memberId)
        await resubscribe()
        await groupChanged()
    }

    public func approve(requestId: String) async throws {
        try await desk.approve(requestId: requestId)
        await publishPrompts()
        if let group = await store.current { setState(.member(group)) }
        await resubscribe()
    }

    public func reject(requestId: String) async throws {
        try await desk.reject(requestId: requestId)
        await publishPrompts()
    }

    // MARK: - Connection

    private func connect() async {
        guard running, !connecting, !transport.isConnected else { return }
        connecting = true
        await connectOnce()
        connecting = false
        if reconnectRequested {
            reconnectRequested = false
            await reconnect()
        }
    }

    private func connectOnce() async {
        let group = await store.current
        let switched: Bool
        if let group { switched = await engine.presenceGateOpen(group) } else { switched = false }
        let willTimestamp = now()
        let will: Data
        do {
            will = try PresenceCodec.encode(identity: identity, isOnline: false, timestamp: willTimestamp, group: group, deviceId: deviceId)
        } catch { return }
        do {
            try await transport.connect(willPayload: will, willTopic: PresenceTopics.willTopic(
                memberId: identity.memberId, groupId: group?.groupId, switched: switched))
        } catch MqttTransportError.alreadyConnecting {
            return
        } catch {
            scheduleReconnect()
            return
        }
        backoffSeconds = 2
        willKid = group.flatMap(GroupKeys.stamp)
        presenceSwitched = switched
        sessionStart = willTimestamp
        await resubscribe()
        if let group {
            publishPresence(isOnline: true, group: group)
            await engine.requestRefresh(reason: "connected")
            // A joiner was written into the roster by its inviter, unsigned; sign it so relays can
            // pass on later changes (3.1, Android ensureMyProfileSigned).
            if let signed = try? await store.updateMyProfile(identity: identity, now: now()) {
                setState(.member(signed))
                await engine.broadcast(signed, changeType: .nameChanged, changedMemberId: identity.memberId)
            }
        }
        transport.flushPendingQueue()
        await coordinator?.start()
        await groupChanged()
        // Peers see where we are as soon as we're back, then every 5 minutes at least.
        if !backgroundWake {
            await sendHeartbeatFix()
            startHeartbeat()
        }
    }

    /// The last-will is fixed at connect, so a new family (or a new key) needs a new session.
    private func reconnect() async {
        guard !connecting else { reconnectRequested = true; return }
        transport.disconnect()
        await connect()
    }

    private func resubscribe() async {
        let group = await store.current
        let peers = group?.members.map(\.memberId).filter { $0 != identity.memberId } ?? []
        _ = await transport.restoreSubscriptions(groupId: group?.groupId, peerMemberIds: peers, presenceSwitched: presenceSwitched)
    }

    private func scheduleReconnect() {
        guard running else { return }
        let delay = backoffSeconds
        backoffSeconds = min(backoffSeconds * 2, 60)
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            await self?.connect()
        }
    }

    fileprivate func connectionChanged(_ state: MqttConnectionState) {
        onEvent(.connection(state))
        if state == .disconnected, running { scheduleReconnect() }
    }

    // MARK: - Inbound

    private func startInbound() {
        guard inbound == nil else { return }
        let (stream, continuation) = AsyncStream<(String, Data, Bool)>.makeStream(bufferingPolicy: .bufferingNewest(500))
        inbound = continuation
        let bridge = TransportBridge(continuation: continuation) { [weak self] state in
            Task { [weak self] in await self?.connectionChanged(state) }
        }
        self.bridge = bridge
        transport.delegate = bridge
        Task { [weak self] in
            for await (topic, payload, retained) in stream { await self?.route(topic: topic, payload: payload, retained: retained) }
        }
    }

    private func route(topic: String, payload: Data, retained: Bool = false) async {
        let me = identity.memberId
        switch topic {
        case Topics.joinApproval(joinerMemberId: me):
            guard let coordinator else { return }
            let decision = await coordinator.handle(payload: payload)
            switch decision {
            case .approved(let group):
                self.coordinator = nil
                try? pendingJoinSlot.delete()
                setState(.member(group))
                await reconnect()
            case .rejected:
                self.coordinator = nil
                try? pendingJoinSlot.delete()
                setState(.rejected)
            case .ignore:
                break
            }
        case Topics.joinRequest(inviterMemberId: me):
            switch await desk.handle(payload: payload) {
            case .prompted: await publishPrompts()
            case .dropped(let reason):
                // A member's ID with someone else's keys is an impersonation attempt; the rest
                // are failed checks (a spent, expired or forged invite).
                security.record(reason == "member ID with different keys" ? .impersonatedJoin : .joinRequestRejected,
                                memberId: nil, detail: reason)
            default: break
            }
            if let group = await store.current, case .member = state { setState(.member(group)) }
        case Topics.groupSyncInbox(memberId: me):
            let before = await store.current?.members.map(\.memberId)
            let event = await engine.handleGroupSync(payload: payload)
            if case .outcome(.removed) = event { setState(.removed); return }
            if let group = await store.current {
                setState(.member(group))
                if group.members.map(\.memberId) != before { await resubscribe() }
                await groupChanged()
            }
        case Topics.syncRequest(memberId: me):
            _ = await engine.handleRefreshRequest(payload: payload)
        case Topics.groupAckInbox(memberId: me):
            await engine.handleAck(payload: payload)
        case Topics.locationInbox(memberId: me):
            guard let (sender, body) = await engine.openFromPeer(payload),
                  let fix = LocationCodec.read(body, sender: sender),
                  location.book.accept(fix) else { return }
            saveLocation()
            onEvent(.locations(location.book.latest))
        default:
            if let group = await store.current, topic == Topics.groupAck(groupId: group.groupId) {
                await engine.handleAck(payload: payload)
            } else if let group = await store.current, let sender = PresenceTopics.sender(of: topic, groupId: group.groupId) {
                // Either presence topic (4.2); the same update may come on both mid-switch.
                guard !payload.isEmpty else { return } // a cleared retained copy
                guard let member = group.findMember(byId: sender) else {
                    // Usually someone who joined while we were away (Android #49). A retained copy
                    // is a leftover handed over on subscribe, not new activity: not logged.
                    if !retained { engine.recordStranger(sender, roster: group, detail: "presence") }
                    await engine.catchUpOnUnknownSender(sender)
                    return
                }
                let status: PresenceStatus
                switch PresenceCodec.read(payload, sender: member, group: group) {
                case .status(let read): status = read
                case .unknownKey: await engine.unknownGroupKeySeen(from: sender); return
                case .invalid: return
                }
                if sender != me {
                    // Authenticated, so the version it advertises can be recorded (§6.2 rule 2).
                    await engine.recordSighting(memberId: sender, protocolVersion: status.protocolVersion)
                    await groupChanged()
                }
                if sender == me {
                    // Our own retained presence comes back with our own ID; another install online means
                    // our phrase is in use elsewhere (§6.2 rule 4).
                    guard let other = status.deviceId,
                          CloneDetector.ownPresenceIsAnotherDevice(isOnline: status.isOnline, deviceId: other, myDeviceId: deviceId) else { return }
                    // Logged every time; the notification below is capped to once per 6 hours.
                    security.record(.identityInUseElsewhere, memberId: me, detail: "device \(other.prefix(8))")
                    let t = now()
                    if let last = lastSelfCloneAlert, t - last < CloneDetector.cooldown { return }
                    lastSelfCloneAlert = t
                    onEvent(.identityAlert("Your account is signed in on another phone. If that isn't you, your recovery phrase may be known to someone else."))
                    return
                }
                if let device = status.deviceId,
                   cloneDetector.observe(memberId: sender, deviceId: device, isOnline: status.isOnline, now: now()) {
                    security.record(.memberOnTwoPhones, memberId: sender, detail: "device \(device.prefix(8))")
                    onEvent(.identityAlert("\(member.displayName) is signed in on another phone. If that wasn't them, they should check their phones."))
                }
                // Replay check, with the sudden-disconnect exception for the current session's will.
                guard presenceBook.accept(status, from: sender) else { return }
                presence = presenceBook.statuses
                try? presenceSlot.writeJSON(presenceBook)
                onEvent(.presence(presence))
            }
        }
    }

    // MARK: - Location (B2)

    /// A new fix from this phone's CoreLocation. Sent to every member unless sharing is paused or
    /// it adds nothing over the last one sent (Android LocationPublishPolicy).
    public func shareLocation(_ fix: LocationUpdate) async {
        guard !location.paused, case .member = state else { return }
        if let last = location.lastSent, LocationPublishPolicy.isRedundant(previous: last, next: fix) { return }
        await publishLocation(fix)
    }

    /// "Share my location": off stops publishing at once and on every relaunch until it's on again.
    public func setSharingPaused(_ paused: Bool) async {
        location.paused = paused
        saveLocation()
        onEvent(.sharingPaused(paused))
        if paused { heartbeat?.cancel(); heartbeat = nil } else { await sendHeartbeatFix(); startHeartbeat() }
    }

    public nonisolated var sharingPausedAtLaunch: Bool { (try? locationSlot.readJSON(LocationState.self))?.paused ?? false }

    /// The last fix this phone shared, or nil if it has never shared in its current family (and
    /// after leaving). Background wake-ups are armed around it, even before the node starts.
    public nonisolated var savedLastFix: LocationUpdate? { (try? locationSlot.readJSON(LocationState.self))?.lastSent }

    /// A fix CoreLocation delivered while the app is in the background: connect if needed, send
    /// it, and disconnect again if we weren't already running. The caller holds a background task.
    public func shareFromBackground(_ fix: LocationUpdate) async {
        let wasRunning = running
        if !wasRunning {
            backgroundWake = true
            await start()
        }
        await shareLocation(fix)
        if !wasRunning {
            try? await Task.sleep(nanoseconds: 1_000_000_000)   // let the QoS 0 publishes leave
            backgroundWake = false
            await stop()
        }
    }

    private func publishLocation(_ fix: LocationUpdate) async {
        guard case .member(let group) = state, transport.isConnected,
              let plaintext = try? LocationCodec.plaintext(for: fix, identity: identity) else {
            // Not connected: keep it; the next connect sends it, re-stamped.
            location.lastSent = location.lastSent.map { $0.timestamp > fix.timestamp ? $0 : fix } ?? fix
            if location.book.accept(fix) { onEvent(.locations(location.book.latest)) }
            saveLocation()
            return
        }
        for peer in group.members where peer.memberId != identity.memberId {
            guard let sealed = await engine.sealForPeer(plaintext, to: peer) else { continue }
            // QoS 0: a position supersedes itself, so redelivery only wastes in-flight slots (§4).
            transport.publish(topic: Topics.locationInbox(memberId: peer.memberId), payload: sealed, qos: .qos0, retained: false)
        }
        location.lastSent = fix
        _ = location.book.accept(fix)
        saveLocation()
        onEvent(.locations(location.book.latest))
    }

    /// The heartbeat re-stamps the last fix with the current time, and so re-signs it (§6.2).
    private func sendHeartbeatFix() async {
        guard !location.paused, let last = location.lastSent else { return }
        await publishLocation(LocationUpdate(memberId: last.memberId, latitude: last.latitude, longitude: last.longitude,
                                             accuracy: last.accuracy, timestamp: max(now(), last.timestamp + 1),
                                             speed: last.speed, bearing: last.bearing))
    }

    private func startHeartbeat() {
        guard heartbeat == nil, !location.paused else { return }
        heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.locationHeartbeatSeconds * 1_000_000_000)
                guard !Task.isCancelled else { return }
                await self?.heartbeatTick()
            }
        }
    }

    private func heartbeatTick() async {
        guard let last = location.lastSent, now() - last.timestamp >= LocationPublishPolicy.maxSilenceMs - 60_000 else { return }
        await sendHeartbeatFix()
    }

    private func saveLocation() { try? locationSlot.writeJSON(location) }

    // MARK: - Security log

    public nonisolated var securityLog: SecurityLog { security.snapshot }

    /// A plain-text report for the user to send wherever they choose (Mail, Messages, a
    /// monitoring app). Nothing is sent from here.
    public func securityReport(appVersion: String) async -> String {
        security.flush()
        return security.snapshot.report(group: await store.current, myMemberId: identity.memberId, appVersion: appVersion, now: now())
    }

    // MARK: - Helpers

    /// Publishes our presence where the switch rules put it (4.2): retained only on the will's
    /// topic, so an abrupt death overwrites it. Online carries this connection's session start.
    /// Once per (group, switched) state, the retained copy on the other topic is cleared.
    private func publishPresence(isOnline: Bool, group: GroupDefinition) {
        guard let payload = try? PresenceCodec.encode(identity: identity, isOnline: isOnline, timestamp: now(), group: group,
                                                      deviceId: deviceId, sessionStart: isOnline ? sessionStart : nil) else { return }
        for target in PresenceTopics.publishTargets(memberId: identity.memberId, groupId: group.groupId, switched: presenceSwitched) {
            transport.publish(topic: target.topic, payload: payload, qos: .qos1, retained: target.retained)
        }
        let state = "\(group.groupId):\(presenceSwitched)"
        if presenceBook.clearedStaleFor != state {
            transport.clearRetained(topic: PresenceTopics.staleTopic(memberId: identity.memberId, groupId: group.groupId,
                                                                     switched: presenceSwitched))
            presenceBook.clearedStaleFor = state
            try? presenceSlot.writeJSON(presenceBook)
        }
    }

    /// After the family's state or a member's advertised version changed: add a group key if a
    /// removal calls for one and everyone can follow (3.3), re-seal the last-will under a new
    /// current key, and refresh who needs to update.
    private func groupChanged() async {
        guard case .member = state, var group = await store.current else { return }
        let gateOpen = await engine.rotationGateOpen(group)
        if GroupKeys.rotationDue(group), gateOpen,
           let rotated = try? await store.rotateKeyIfDue(gateOpen: { _ in gateOpen }) {
            group = rotated
            setState(.member(rotated))
            await engine.broadcast(rotated, changeType: .versionSync)
        }
        // The will's key and topic are fixed at connect: a new current key (3.3) or the presence
        // gate opening or closing (4.2) needs a reconnect.
        let gate = await engine.presenceGateOpen(group)
        if transport.isConnected, GroupKeys.stamp(group) != willKid || gate != presenceSwitched {
            willKid = GroupKeys.stamp(group)
            await reconnect()
        }
        let members = Set(group.members.map(\.memberId))
        if !Set(location.book.latest.keys).subtracting(members).isEmpty {
            location.book.keepOnly(members)
            saveLocation()
            onEvent(.locations(location.book.latest))
        }
        if Set(presenceBook.statuses.keys).subtracting(members).isEmpty == false {
            presenceBook.keepOnly(members)
            presence = presenceBook.statuses
            try? presenceSlot.writeJSON(presenceBook)
            onEvent(.presence(presence))
        }
        let outdated = await engine.outdatedMembers(group)
        if outdated != lastNeedsUpdate {
            lastNeedsUpdate = outdated
            onEvent(.needsUpdate(outdated))
        }
    }

    private func resumeJoin(_ pending: PendingJoin) {
        coordinator = JoinCoordinator(identity: identity, pending: pending, publisher: outbox, store: store)
        setState(.joining(familyName: pending.familyName, inviterName: pending.inviterName,
                          fingerprint: JoinFlow.fingerprint(of: identity)))
    }

    private func publishPrompts() async {
        onEvent(.prompts(await desk.prompts))
    }

    private func setState(_ next: NodeState) {
        state = next
        onEvent(.state(next))
    }
}

/// Sends through the transport; satisfies both the sync engine's and the join flow's outbox.
final class TransportOutbox: GroupSyncOutbox, JoinPublisher, @unchecked Sendable {
    private let transport: MqttTransport
    init(transport: MqttTransport) { self.transport = transport }

    func send(topic: String, payload: Data, qos: Int, retained: Bool) async {
        transport.publish(topic: topic, payload: payload, qos: qos == 0 ? .qos0 : .qos1, retained: retained)
    }

    func publish(topic: String, payload: Data, retained: Bool) async {
        transport.publish(topic: topic, payload: payload, qos: .qos1, retained: retained)
    }
}

/// Hands every inbound message to one ordered stream, so they're handled in arrival order.
final class TransportBridge: MqttTransportDelegate, @unchecked Sendable {
    private let continuation: AsyncStream<(String, Data, Bool)>.Continuation
    private let onState: @Sendable (MqttConnectionState) -> Void

    init(continuation: AsyncStream<(String, Data, Bool)>.Continuation, onState: @escaping @Sendable (MqttConnectionState) -> Void) {
        self.continuation = continuation
        self.onState = onState
    }

    func transport(_ transport: MqttTransport, didReceiveMessage payload: Data, onTopic topic: String) {
        continuation.yield((topic, payload, false))
    }

    func transport(_ transport: MqttTransport, didReceiveMessage payload: Data, onTopic topic: String, retained: Bool) {
        continuation.yield((topic, payload, retained))
    }

    func transport(_ transport: MqttTransport, didChangeConnectionState state: MqttConnectionState) {
        onState(state)
    }
}
