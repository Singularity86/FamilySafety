import CocoaMQTT
import CryptoKit
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
    /// Every chat message held, after any change (B3).
    case chat(ChatBook)
    /// Crash alerts this phone raised and each member's copy, after any change (B3).
    case emergency(EmergencyOutbox)
    /// The family's file library as this phone sees it, after any change (B5).
    case files(FileLibrary)
    /// This phone's places and alert settings, after any change (B6).
    case places(PlaceWatch)
    /// Someone arrived at or left a place, or is driving fast: tell the person holding this phone.
    case placeAlert(PlaceEvent, memberName: String)
}

public enum NodeError: Error, Equatable {
    /// The family's 500 MB of shared files would be exceeded.
    case storageFull
    /// The file couldn't be read, or stored on this phone.
    case fileUnreadable
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
    private var chat: ChatBook
    private let chatSlot: SecretSlot
    /// False when saved chat couldn't be read: nothing is then saved over it (F14).
    private let chatReadable: Bool
    /// The conversation open on screen: what arrives there is read at once.
    private var activeConversation: String?
    private var alerts: EmergencyOutbox
    private let emergencySlot: SecretSlot
    /// Wakes when the next alert copy falls due.
    private var alertTimer: Task<Void, Never>?
    /// Chat history back-fill (B5): announce, request, respond.
    private var replication: ChatReplication
    private var announceTimer: Task<Void, Never>?
    /// The family vault (B6): container, version mark and document chunks. No code or key is ever kept here.
    private let vault: VaultStore?
    /// Places and speed alerts (B6), local to this phone.
    private var placeWatch: PlaceWatch
    private let placesSlot: SecretSlot
    /// Location history (B6): 30 days of everyone's fixes, and its back-fill.
    private var history: LocationHistory
    private let historySlot: SecretSlot
    private var historySave: Task<Void, Never>?
    private var locationReplication: LocationReplication
    /// Shared files (B5): the library, and where chunk ciphertext is kept.
    private var library: FileLibrary
    private let librarySlot: SecretSlot
    private let chunks: ChunkStore?
    private var repairTimer: Task<Void, Never>?
    /// The roster last seen, so a newcomer gets a fresh manifest (Android syncNewMember).
    private var knownMembers: Set<String>?
    /// When each member last asked us to re-send the file list (`files/request`, unauthenticated).
    private var fileRequestSeen: [String: Int64] = [:]
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
                chatSlot: SecretSlot = InMemorySlot(), emergencySlot: SecretSlot = InMemorySlot(),
                librarySlot: SecretSlot = InMemorySlot(), historySlot: SecretSlot = InMemorySlot(), chunkStore: ChunkStore? = nil,
                placesSlot: SecretSlot = InMemorySlot(), vaultStore: VaultStore? = nil,
                onEvent: @escaping @Sendable (NodeEvent) -> Void) throws {
        self.vault = vaultStore
        // On every phone, from first run, whether or not anyone ever uses a vault (spec §6.8).
        _ = try? vaultStore?.container()
        vaultStore?.forgetViews()
        self.placesSlot = placesSlot
        self.placeWatch = (try? placesSlot.readJSON(PlaceWatch.self)) ?? PlaceWatch()
        self.historySlot = historySlot
        self.history = (try? historySlot.readJSON(LocationHistory.self)) ?? LocationHistory()
        self.librarySlot = librarySlot
        self.library = (try? librarySlot.readJSON(FileLibrary.self)) ?? FileLibrary()
        self.chunks = chunkStore
        chunkStore?.clearViews()
        self.emergencySlot = emergencySlot
        self.alerts = (try? emergencySlot.readJSON(EmergencyOutbox.self)) ?? EmergencyOutbox()
        self.chatSlot = chatSlot
        do {
            self.chat = try chatSlot.readJSON(ChatBook.self) ?? ChatBook()
            self.chatReadable = true
        } catch {
            self.chat = ChatBook()
            self.chatReadable = false
        }
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
        self.memberId = identity.memberId
        self.replication = ChatReplication(me: identity.memberId)
        self.locationReplication = LocationReplication(me: identity.memberId)
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

    public nonisolated let memberId: String

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
                onEvent(.chat(chat))
                onEvent(.emergency(alerts))
                onEvent(.files(library))
                onEvent(.places(placeWatch))
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
        alertTimer?.cancel()
        alertTimer = nil
        announceTimer?.cancel()
        announceTimer = nil
        repairTimer?.cancel()
        repairTimer = nil
        flushHistory()
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
        chat = ChatBook()
        saveChat()
        onEvent(.chat(chat))
        alerts = EmergencyOutbox()
        saveAlerts()
        onEvent(.emergency(alerts))
        for id in library.entries.keys { chunks?.deleteAll(id) }
        library = FileLibrary()
        saveLibrary()
        history = LocationHistory()
        flushHistory()
        onEvent(.files(library))
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
        await sendUnsentChat()
        // Every copy still owed goes at once on a reconnect (§6.3).
        await flushAlerts(everything: true)
        await backfillChat()
        await announceFiles(force: true)
        await runRepairs()
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
                security.record(InviterDesk.securityKind(forDropped: reason), memberId: nil, detail: reason)
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
            if sender.memberId != identity.memberId {
                let events = placeWatch.evaluate(fix, now: now())
                try? placesSlot.writeJSON(placeWatch)
                for e in events { onEvent(.placeAlert(e, memberName: sender.displayName)) }
            }
            history.add([fix], now: now())
            saveHistory()
            saveLocation()
            onEvent(.locations(location.book.latest))
        case Topics.chat(memberId: me):
            await receiveChat(payload)
        case Topics.replicationAnnounceInbox(memberId: me), Topics.replicationRequest(memberId: me), Topics.replicationData(memberId: me):
            await handleReplication(topic: topic, payload: payload)
        case Topics.chatReceipt(memberId: me), Topics.chatRead(memberId: me):
            guard let (sender, body) = await engine.openFromPeer(payload),
                  let receipt = ChatCodec.readReceipt(body) else { return }
            // A crash alert owed to this member: only their own signed receipt settles their copy.
            if receipt.recipientId == sender.memberId, receipt.status == .delivered || receipt.status == .read,
               alerts.markDelivered(alertId: receipt.messageId, by: sender.memberId, now: now()) {
                saveAlerts()
                onEvent(.emergency(alerts))
            }
            guard chat.apply(receipt, from: sender.memberId) else { return }
            saveChat()
            onEvent(.chat(chat))
        case Topics.fileRepair(memberId: me):
            await serveRepair(payload)
        case Topics.fileRequest(memberId: me):
            await answerFileRequest(payload)
        case Topics.vaultRepair(memberId: me):
            await serveVaultRepair(payload)
        case Topics.fileAvailability(memberId: me):
            guard let group = await store.current, let plain = FileCodec.openMessage(payload, group: group),
                  let a = try? JSONDecoder().decode(FileHoldingsAnnouncement.self, from: plain) else { return }
            library.record(a, from: a.announcerId, members: Set(group.members.map(\.memberId)).subtracting([me]))
            saveLibrary()
            onEvent(.files(library))
            // A peer who has never heard of a file we shared missed its manifest: a share published
            // into a connection that had already died was otherwise never sent again (since 1.15.8).
            let t = now()
            if group.containsMember(a.announcerId), !library.sharedButUnknown(to: a, me: me, now: t).isEmpty,
               lastManifestResendAtMs.map({ t - $0 >= FileLibrary.manifestResendSpacingMs }) ?? true {
                lastManifestResendAtMs = t
                await publishManifest()
            }
        default:
            if let group = await store.current, topic == Topics.vaultContainer(groupId: group.groupId) {
                receiveVaultContainer(payload, group: group)
            } else if let group = await store.current, topic.hasPrefix(Self.vaultChunkPrefix(group.groupId)) {
                receiveVaultChunk(topic: topic, payload: payload, group: group)
            } else if let group = await store.current, topic == Topics.fileManifest(groupId: group.groupId) {
                await receiveManifest(payload, group: group)
            } else if let group = await store.current, topic.hasPrefix(Self.chunkPrefix(group.groupId)) {
                await receiveChunk(topic: topic, payload: payload, group: group)
            } else if let group = await store.current, topic == Topics.groupAck(groupId: group.groupId) {
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
        history.add([fix], now: now())
        saveHistory()
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

    // MARK: - Chat (B3)

    /// Sends a message to the family (`to` nil) or privately to one member. Stored first, so a
    /// message written offline goes out on the next connect.
    @discardableResult
    public func sendChat(_ text: String, to memberId: String? = nil) async -> ChatMessage? {
        let content = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !content.isEmpty, case .member(let group) = state else { return nil }
        if let memberId, memberId == identity.memberId || group.findMember(byId: memberId) == nil { return nil }
        let message = ChatCodec.compose(content: content, to: memberId, groupId: group.groupId, identity: identity, now: now())
        chat.insert(message)
        saveChat()
        onEvent(.chat(chat))
        await deliver(message)
        return chat.messages[message.messageId]
    }

    /// The conversation now on screen, or nil. Opening one marks it read and tells the senders.
    public func openConversation(_ conversationId: String?) async {
        activeConversation = conversationId
        guard let conversationId else { return }
        let unread = chat.markRead(conversationId)
        guard !unread.isEmpty else { return }
        saveChat()
        onEvent(.chat(chat))
        for message in unread { await sendReceipt(.read, for: message.messageId, to: message.senderId) }
    }

    /// The conversation id for chatting privately with `memberId`.
    public func privateConversationId(with memberId: String) -> String {
        ChatBook.privateConversationId(identity.memberId, memberId)
    }

    private func receiveChat(_ payload: Data) async {
        guard case .member(let group) = state, let (sender, body) = await engine.openFromPeer(payload),
              sender.memberId != identity.memberId else { return }
        // A bad author signature, or a group message naming another family, is dropped (as on Android, not logged).
        guard var message = ChatCodec.read(body, sender: sender, me: identity.memberId, groupId: group.groupId) else { return }
        // Already held: confirm it again, so a sender whose receipt was lost stops retrying (F27).
        // Only DELIVERED: a re-send says nothing about reading.
        if chat.contains(message.messageId) {
            await sendReceipt(.delivered, for: message.messageId, to: sender.memberId)
            return
        }
        message.isRead = activeConversation == message.conversationId
        chat.insert(message)
        saveChat()
        onEvent(.chat(chat))
        await sendReceipt(message.isRead ? .read : .delivered, for: message.messageId, to: sender.memberId)
    }

    /// One E2EE copy per recipient: everyone else for group chat, the one person for a private message.
    private func deliver(_ message: ChatMessage) async {
        guard case .member(let group) = state, transport.isConnected, let plaintext = try? ChatCodec.plaintext(message) else { return }
        let recipients = group.members.filter {
            $0.memberId != identity.memberId && (message.recipientId == nil || $0.memberId == message.recipientId)
        }
        var sent = recipients.isEmpty
        for peer in recipients {
            guard let sealed = await engine.sealForPeer(plaintext, to: peer) else { continue }
            if transport.publish(topic: Topics.chat(memberId: peer.memberId), payload: sealed, qos: .qos1) { sent = true }
        }
        // A private message to someone who has left can never go; group chat waits for a connect.
        if message.recipientId != nil && recipients.isEmpty { chat.setStatus(message.messageId, .failed) }
        else if sent { chat.setStatus(message.messageId, .sent) }
        saveChat()
        onEvent(.chat(chat))
    }

    private func sendUnsentChat() async {
        // Crash alerts have their own per-member resends.
        for message in chat.unsent where !alerts.isAlert(message.messageId) { await deliver(message) }
    }

    // MARK: - Chat history back-fill (B5)

    /// On connect: ask for anything newer in the conversations we hold, then tell everyone what we
    /// hold so they can ask us. Then announce every 30 minutes while running.
    private func backfillChat() async {
        guard case .member(let group) = state else { return }
        let peers = group.members.map(\.memberId).filter { $0 != identity.memberId }
        for (peerId, request) in replication.fullSync(book: chat, peers: peers, groupId: group.groupId, now: now())
            + locationReplication.fullSync(history: history, peers: peers, now: now()) {
            await sendReplication(request, topic: Topics.replicationRequest(memberId: peerId), to: peerId)
        }
        await announceChat()
        guard announceTimer == nil else { return }
        announceTimer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(ChatReplication.announceIntervalMs) * 1_000_000)
                guard !Task.isCancelled else { return }
                await self?.announceChat()
            }
        }
    }

    private func announceChat() async {
        guard case .member(let group) = state, transport.isConnected else { return }
        // This family's only: a phone that moved families still holds the old one's (since 1.15.8).
        let roster = Set(group.members.map(\.memberId))
        let locations = history.summaries().filter { roster.contains($0.memberId) }
        for peer in group.members where peer.memberId != identity.memberId {
            let chatPart = replication.announcement(for: peer.memberId, book: chat, groupId: group.groupId, now: now())
            guard !chatPart.chatDataSummary.isEmpty || !locations.isEmpty else { continue }
            let a = DataAvailabilityAnnouncement(announcerId: identity.memberId, locationDataSummary: locations,
                                                 chatDataSummary: chatPart.chatDataSummary, timestamp: now())
            await sendReplication(a, topic: Topics.replicationAnnounceInbox(memberId: peer.memberId), to: peer.memberId, qos: .qos0)
        }
    }

    private func handleReplication(topic: String, payload: Data) async {
        guard case .member(let group) = state, let (sender, body) = await engine.openFromPeer(payload),
              sender.memberId != identity.memberId else { return }
        let data = Data(body.utf8)
        let me = identity.memberId
        let members = Set(group.members.map(\.memberId))
        switch topic {
        case Topics.replicationAnnounceInbox(memberId: me):
            guard let a = try? JSONDecoder().decode(DataAvailabilityAnnouncement.self, from: data) else { return }
            let asks = replication.handle(a, from: sender.memberId, book: chat, groupId: group.groupId, now: now())
                + locationReplication.handle(a, from: sender.memberId, history: history, members: members, now: now())
            for r in asks {
                await sendReplication(r, topic: Topics.replicationRequest(memberId: sender.memberId), to: sender.memberId)
            }
        case Topics.replicationRequest(memberId: me):
            guard let r = try? JSONDecoder().decode(ReplicationRequest.self, from: data) else { return }
            let response = r.dataType == .chatMessages
                ? replication.respond(to: r, from: sender.memberId, book: chat, groupId: group.groupId, now: now())
                : locationReplication.respond(to: r, from: sender.memberId, history: history, members: members, now: now())
            guard let response else { return }
            await sendReplication(response, topic: Topics.replicationData(memberId: sender.memberId), to: sender.memberId)
        default:
            guard let response = try? JSONDecoder().decode(ReplicationResponse.self, from: data) else { return }
            var versions: [String: Int] = [:]
            for author in response.messages.map(\.senderId) + response.locations.map(\.memberId) where versions[author] == nil {
                versions[author] = await engine.advertisedVersion(of: author) ?? 0
            }
            let next: ReplicationRequest?
            if response.dataType == .locationHistory {
                let got = locationReplication.handle(response, from: sender, roster: group, authorVersion: { versions[$0] }, now: now())
                if history.add(got.fixes, now: now()) > 0 { saveHistory() }
                next = got.next
            } else {
                let got = replication.handle(response, from: sender, roster: group, authorVersion: { versions[$0] }, now: now())
                var changed = false
                for m in got.messages { changed = chat.insert(m) || changed }
                if changed {
                    chat.prune(now: now(), me: identity.memberId, members: members, groupId: group.groupId)
                    saveChat()
                    onEvent(.chat(chat))
                }
                next = got.next
            }
            if let next {
                await sendReplication(next, topic: Topics.replicationRequest(memberId: sender.memberId), to: sender.memberId)
            }
        }
    }

    // MARK: - Vault (B6)

    static func vaultChunkPrefix(_ groupId: String) -> String { "familysafe/group/\(groupId)/vault/chunk/" }

    public struct VaultView: Sendable {
        public let items: [VaultItem]
        public let removed: [VaultItem]
    }

    /// The key a code opens, derived off this actor (Argon2id is slow on purpose). Nil outside a family.
    public nonisolated func vaultKey(code: String) async -> SymmetricKeyBox? {
        guard let groupId = await store.current?.groupId else { return nil }
        return try? SymmetricKeyBox(VaultCrypto.key(code: code, groupId: groupId))
    }

    /// What this key opens: every slot is tried, and an empty result is an ordinary vault.
    public func openVault(_ key: SymmetricKeyBox) -> VaultView {
        let all = (try? vault?.container()).flatMap { $0 }.map { VaultCrypto.openAll($0, key: key.key) } ?? []
        return VaultView(items: all.filter(\.isActive), removed: all.filter { !$0.isActive })
    }

    /// Adds a document: chunks under a fresh content key that lives only inside the slot, two slots
    /// sealed, the container published, then the chunks.
    public func addToVault(_ key: SymmetricKeyBox, fileAt url: URL, name: String, mimeType: String) async throws -> VaultView {
        guard case .member(let group) = state, let vault else { throw NodeError.notInFamily }
        guard openVault(key).items.count < VaultCrypto.maxItems else { throw NodeError.storageFull }
        let fileId = UUID().uuidString.lowercased()
        let contentKey = SymmetricKey(size: .bits256)
        var hasher = SHA256()
        var size = 0
        var count = 0
        do {
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            while let piece = try handle.read(upToCount: VaultStore.chunkSize), !piece.isEmpty {
                size += piece.count
                guard size <= VaultStore.maxItemBytes else { throw NodeError.storageFull }
                hasher.update(data: piece)
                try vault.writeOwnChunk(fileId: fileId, index: count, ciphertext: try FileCipher.seal(piece, key: contentKey))
                count += 1
            }
        } catch {
            vault.discard(fileId)
            throw error as? NodeError ?? NodeError.fileUnreadable
        }
        vault.addOwn(fileId: fileId, chunkCount: count)
        let payload = VaultCrypto.fitName(VaultSlotPayload(
            fileId: fileId, name: name, mimeType: mimeType, sizeBytes: Int64(size),
            contentHash: hasher.finalize().map { String(format: "%02x", $0) }.joined(),
            contentKeyHex: Hex.encode(contentKey.withUnsafeBytes { Array($0) }), chunkCount: count,
            addedAt: now(), addedBy: identity.memberId))
        guard let next = try? vault.update({ VaultCrypto.write(payload, into: $0, key: key.key) }) else {
            vault.discard(fileId)
            throw NodeError.storageFull
        }
        publishVaultContainer(next, group: group)
        for i in 0..<count { await publishVaultChunk(fileId: fileId, index: i, total: count, group: group) }
        return openVault(key)
    }

    /// Removes (recoverably) or puts back: the slot is rewritten with the state flipped, never erased.
    public func setVaultItem(_ key: SymmetricKeyBox, _ item: VaultItem, active: Bool) -> VaultView {
        guard case .member(let group) = state, let vault else { return openVault(key) }
        var p = item.payload
        p.state = active ? VaultSlotPayload.active : VaultSlotPayload.reclaimed
        p.revision = now()
        if let next = try? vault.update({ VaultCrypto.write(VaultCrypto.fitName(p), into: $0, key: key.key, existing: item.slots) }) {
            publishVaultContainer(next, group: group)
        }
        return openVault(key)
    }

    /// A temporary plaintext copy to view, once every chunk is here and the whole verifies; otherwise
    /// asks the family for what's missing and returns nil.
    public func vaultFile(_ item: VaultItem) -> URL? {
        guard let vault, let raw = Hex.decode(item.payload.contentKeyHex), raw.count == 32 else { return nil }
        let key = SymmetricKeyBox(SymmetricKey(data: Data(raw)))
        if vault.blob(item.id)?.isComplete == true,
           vault.verifiedHash(fileId: item.id, chunkCount: item.payload.chunkCount, key: key) == item.payload.contentHash.lowercased(),
           let url = try? vault.materialize(fileId: item.id, name: item.payload.name, chunkCount: item.payload.chunkCount, key: key) {
            return url
        }
        requestVaultChunks(item)
        return nil
    }

    /// Clears everything decrypted for viewing. Call when a vault closes.
    public nonisolated func closeVault() { vault?.forgetViews() }

    private func requestVaultChunks(_ item: VaultItem) {
        guard case .member(let group) = state, let vault else { return }
        let missing = vault.missing(item.id, chunkCount: item.payload.chunkCount)
        // Sealed like files/repair, so only the family can read who is fetching what, or trigger a reply.
        guard !missing.isEmpty,
              let body = try? FileCodec.sealMessage(VaultChunkRequest(requesterId: identity.memberId, fileId: item.id, missing: missing,
                                                                     timestamp: now()), group: group)
        else { return }
        for peer in group.members where peer.memberId != identity.memberId {
            transport.publish(topic: Topics.vaultRepair(memberId: peer.memberId), payload: body, qos: .qos1)
        }
    }

    /// Versioned by wall clock, above anything seen; signed over `{version}|{data}`; retained.
    private func publishVaultContainer(_ bytes: Data, group: GroupDefinition) {
        guard let vault else { return }
        let version = max(vault.version + 1, now())
        vault.setVersion(version)
        let data = bytes.base64EncodedString()
        guard let sig = try? AuthorSignatures.sign(VaultContainerMessage.signingPayload(version: version, data: data), identity: identity),
              let payload = try? JSONEncoder().encode(VaultContainerMessage(version: version, data: data, signerMemberId: identity.memberId, signature: sig))
        else { return }
        transport.publish(topic: Topics.vaultContainer(groupId: group.groupId), payload: payload, qos: .qos1, retained: true)
    }

    /// Taken only when newer than any seen, no more than a day ahead, exactly 131072 bytes, and signed by
    /// someone in our own roster. An unsigned one would let anyone erase the family's vault.
    private func receiveVaultContainer(_ payload: Data, group: GroupDefinition) {
        guard let vault, !payload.isEmpty, let m = try? JSONDecoder().decode(VaultContainerMessage.self, from: payload),
              m.version > vault.version, m.version <= now() + 86_400_000,
              !m.signature.isEmpty, let signer = group.findMember(byId: m.signerMemberId),
              let pub = Hex.decode(signer.ed25519PublicKey), let sig = Hex.decode(m.signature), (try? SodiumRaw.ensureInitialized()) != nil,
              SodiumRaw.signVerifyDetached(signature: sig, message: Array(VaultContainerMessage.signingPayload(version: m.version, data: m.data).utf8),
                                           publicKey: pub),
              let bytes = Data(base64Encoded: m.data), bytes.count == VaultCrypto.containerBytes else { return }
        if (try? vault.write(bytes)) != nil { vault.setVersion(m.version) }
    }

    /// Stored without authentication, within bounds: this phone may hold no code that reads it.
    private func receiveVaultChunk(topic: String, payload: Data, group: GroupDefinition) {
        let parts = topic.dropFirst(Self.vaultChunkPrefix(group.groupId).count).split(separator: "/").map(String.init)
        guard let vault, parts.count == 2, let index = Int(parts[1]),
              let m = try? JSONDecoder().decode(VaultChunkMessage.self, from: payload),
              m.fileId == parts[0], m.chunkIndex == index, let blob = Data(base64Encoded: m.data) else { return }
        vault.storeChunk(fileId: m.fileId, index: index, totalChunks: m.totalChunks, ciphertext: blob)
    }

    /// Answered from the chunk store alone: holding the bytes is the whole qualification.
    private func serveVaultRepair(_ payload: Data) async {
        guard case .member(let group) = state, let vault, let r = Self.openVaultRequest(payload, group: group),
              r.requesterId != identity.memberId, group.containsMember(r.requesterId),
              let (total, indices) = vault.servable(r) else { return }
        for i in indices { await publishVaultChunk(fileId: r.fileId, index: i, total: total, group: group) }
    }

    /// A sealed request (an `EncryptedFileMessage`, as files/repair), or, while phones update, a plaintext one.
    /// Something sealed that won't open is dropped, never read as plaintext.
    static func openVaultRequest(_ payload: Data, group: GroupDefinition) -> VaultChunkRequest? {
        if (try? JSONDecoder().decode(EncryptedFileMessage.self, from: payload)) != nil {
            return FileCodec.openMessage(payload, group: group).flatMap { try? JSONDecoder().decode(VaultChunkRequest.self, from: $0) }
        }
        return try? JSONDecoder().decode(VaultChunkRequest.self, from: payload)
    }

    private func publishVaultChunk(fileId: String, index: Int, total: Int, group: GroupDefinition) async {
        guard let blob = vault?.readChunk(fileId: fileId, index: index),
              let body = try? JSONEncoder().encode(VaultChunkMessage(fileId: fileId, chunkIndex: index, totalChunks: total, data: blob.base64EncodedString()))
        else { return }
        transport.publish(topic: Topics.vaultChunk(groupId: group.groupId, fileId: fileId, chunkIndex: index), payload: body, qos: .qos1)
        try? await Task.sleep(nanoseconds: 15_000_000)
    }

    // MARK: - Places (B6)

    public func savePlace(_ place: Place) {
        placeWatch.save(place)
        savePlaces()
    }

    public func removePlace(_ placeId: String) {
        placeWatch.remove(placeId)
        savePlaces()
    }

    public func setSpeedAlerts(on: Bool, thresholdMph: Int) {
        placeWatch.speedAlertsOn = on
        placeWatch.speedThresholdMph = min(max(thresholdMph, 20), 150)
        savePlaces()
    }

    private func savePlaces() {
        try? placesSlot.writeJSON(placeWatch)
        onEvent(.places(placeWatch))
    }

    // MARK: - Location history (B6)

    /// One member's fixes between two times, oldest first (30 days kept).
    public func history(of memberId: String, from: Int64, to: Int64) -> [LocationUpdate] {
        history.between(memberId, from: from, to: to)
    }

    /// Saves at most every 20 seconds; fixes arrive often and the whole history is rewritten.
    private func saveHistory() {
        guard historySave == nil else { return }
        historySave = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            await self?.flushHistory()
        }
    }

    private func flushHistory() {
        historySave?.cancel()
        historySave = nil
        try? historySlot.writeJSON(history)
    }

    private func sendReplication<T: Encodable>(_ value: T, topic: String, to memberId: String, qos: CocoaMQTTQoS = .qos1) async {
        guard let peer = await store.current?.findMember(byId: memberId),
              let json = try? JSONEncoder().encode(value), let plaintext = String(data: json, encoding: .utf8),
              let sealed = await engine.sealForPeer(plaintext, to: peer) else { return }
        transport.publish(topic: topic, payload: sealed, qos: qos)
    }

    // MARK: - Shared files (B5)

    static func chunkPrefix(_ groupId: String) -> String { "familysafe/group/\(groupId)/files/chunk/" }

    /// Fetch a file that isn't kept automatically (opened by someone).
    public func requestFile(_ fileId: String) async {
        library.want(fileId, now: now())
        saveLibrary()
        onEvent(.files(library))
        await runRepairs()
    }

    /// A temporary plaintext copy to show, if the file is complete here. Cleared on next launch.
    public func openFile(_ fileId: String) async -> URL? {
        guard let group = await store.current, let e = library.entries[fileId], e.state == .complete, let chunks,
              let key = FileKeys.key(group, keyVersion: e.blobKeyVersion, kid: e.file.kid) else { return nil }
        return try? chunks.materialize(fileId: fileId, name: e.file.name, chunkCount: e.file.chunkCount, key: key)
    }

    private func receiveManifest(_ payload: Data, group: GroupDefinition) async {
        guard !payload.isEmpty, let manifest = FileCodec.openManifest(payload, group: group) else { return }
        let outcome = library.apply(manifest, groupId: group.groupId, now: now())
        guard outcome.changed else { return }
        for id in outcome.erase { chunks?.deleteAll(id) }
        saveLibrary()
        onEvent(.files(library))
        await runRepairs()
    }

    private func receiveChunk(topic: String, payload: Data, group: GroupDefinition) async {
        let parts = topic.dropFirst(Self.chunkPrefix(group.groupId).count).split(separator: "/").map(String.init)
        guard parts.count == 2, let index = Int(parts[1]), let chunks, library.accepts(fileId: parts[0]),
              let entry = library.entries[parts[0]],
              case .accept(let ciphertext, let keyVersion) = FileCodec.checkChunk(payload, topicFileId: parts[0], topicIndex: index,
                                                                                   file: entry.file, group: group),
              entry.blobKeyVersion == 0 || entry.blobKeyVersion == keyVersion, !entry.held.contains(index) else { return }
        guard (try? chunks.write(fileId: parts[0], index: index, ciphertext: ciphertext)) != nil else { return }
        guard case .stored(let complete) = library.recordChunk(fileId: parts[0], index: index, keyVersion: keyVersion, now: now()) else { return }
        if complete { await finishFile(parts[0], group: group) }
        saveLibrary()
        onEvent(.files(library))
        scheduleRepairs()
    }

    /// Every chunk is in: decrypt in order and compare with the manifest's hash before calling it complete.
    private func finishFile(_ fileId: String, group: GroupDefinition) async {
        guard let e = library.entries[fileId], let chunks,
              let key = FileKeys.key(group, keyVersion: e.blobKeyVersion, kid: e.file.kid) else { return }
        if chunks.verifiedHash(fileId: fileId, chunkCount: e.file.chunkCount, key: key) == e.file.contentHash.lowercased() {
            library.markComplete(fileId)
            await announceFiles(force: true)
        } else {
            chunks.deleteAll(fileId)
            library.discard(fileId, now: now())
        }
    }

    /// Asks one peer for what's missing of each due file, then sleeps until the next is due.
    private func runRepairs() async {
        defer { scheduleRepairs() }
        guard case .member(let group) = state, transport.isConnected else { return }
        let peers = group.members.map(\.memberId).filter { $0 != identity.memberId }
        for fileId in library.due(now: now()) {
            guard let (peer, request) = library.repairRequest(fileId, me: identity.memberId, peers: peers, now: now()),
                  let sealed = try? FileCodec.sealMessage(request, group: group) else { continue }
            transport.publish(topic: Topics.fileRepair(memberId: peer), payload: sealed, qos: .qos1)
        }
        saveLibrary()
        onEvent(.files(library))
    }

    private func scheduleRepairs() {
        repairTimer?.cancel()
        repairTimer = nil
        guard running, let next = library.nextDueAtMs() else { return }
        let wait = UInt64(max(next - now(), 0)) * 1_000_000
        repairTimer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: wait)
            guard !Task.isCancelled else { return }
            await self?.runRepairs()
        }
    }

    /// A peer asked for chunks of a file we hold whole: re-serve the stored ciphertext verbatim, to the
    /// ordinary group chunk topic (any device with the same gap picks it up), paced.
    private func serveRepair(_ payload: Data) async {
        guard let group = await store.current, let chunks, let plain = FileCodec.openMessage(payload, group: group),
              let r = try? JSONDecoder().decode(ChunkRepairRequest.self, from: plain), r.requesterId != identity.memberId,
              group.containsMember(r.requesterId), let (file, keyVersion, indices) = library.serve(r, from: r.requesterId) else { return }
        for i in indices {
            guard let blob = chunks.read(fileId: file.fileId, index: i),
                  let message = try? FileCodec.chunkMessage(fileId: file.fileId, index: i, total: file.chunkCount, ciphertext: blob,
                                                            keyVersion: keyVersion, kid: file.kid) else { continue }
            transport.publish(topic: Topics.fileChunk(groupId: group.groupId, fileId: file.fileId, chunkIndex: i), payload: message, qos: .qos1)
            try? await Task.sleep(nanoseconds: 15_000_000)   // Android's pacing: don't overrun the in-flight window
        }
    }

    private func announceFiles(force: Bool) async {
        guard case .member(let group) = state, transport.isConnected,
              let a = library.announcement(me: identity.memberId, now: now(), force: force),
              let sealed = try? FileCodec.sealMessage(a, group: group) else { return }
        for peer in group.members where peer.memberId != identity.memberId {
            transport.publish(topic: Topics.fileAvailability(memberId: peer.memberId), payload: sealed, qos: .qos1)
        }
        saveLibrary()
    }

    /// Shares a file with the family: encrypted chunk by chunk under the current key (version 3),
    /// stored as ciphertext, listed in the manifest, then published paced. Nothing plaintext is
    /// written. A file whose exact bytes are already shared is not added twice.
    public func uploadFile(at url: URL, name: String, mimeType: String, essential: Bool = true) async throws {
        guard case .member(let group) = state, let chunks else { throw NodeError.notInFamily }
        let declared = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        guard library.usedBytes + declared <= FileLibrary.maxTotalBytes else { throw NodeError.storageFull }
        let (key, keyVersion, kid) = FileKeys.writeKey(group)
        let fileId = UUID().uuidString.lowercased()
        var hasher = SHA256()
        var size: Int64 = 0
        var count = 0
        do {
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            while let piece = try handle.read(upToCount: FileLibrary.chunkSize), !piece.isEmpty {
                hasher.update(data: piece)
                try chunks.write(fileId: fileId, index: count, ciphertext: try FileCipher.seal(piece, key: key))
                size += Int64(piece.count)
                count += 1
            }
        } catch {
            chunks.deleteAll(fileId)
            throw NodeError.fileUnreadable
        }
        let hash = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard !library.holdsCopy(ofHash: hash) else { chunks.deleteAll(fileId); return }
        guard library.usedBytes + size <= FileLibrary.maxTotalBytes else { chunks.deleteAll(fileId); throw NodeError.storageFull }
        let file = SharedFile(fileId: fileId, name: name, mimeType: mimeType, sizeBytes: size, contentHash: hash,
                              uploaderMemberId: identity.memberId, uploadedAt: now(), chunkCount: count, isEssential: essential, kid: kid)
        library.addOwn(file, keyVersion: keyVersion)
        saveLibrary()
        onEvent(.files(library))
        // The manifest first, so receivers have the entry before its chunks arrive. A pause, because a
        // receiver that handles messages concurrently (Android) can otherwise see a chunk before it has
        // stored the entry, drop it, and leave the whole file to repair.
        await publishManifest()
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        await publishChunks(file, keyVersion: keyVersion, indices: Array(0..<count), group: group)
        await publishManifest()
        await announceFiles(force: true)
    }

    /// Deletes a file for everyone: the tombstone in the manifest erases it on every phone.
    public func deleteFile(_ fileId: String) async {
        guard library.delete(fileId, by: identity.memberId, now: now()) else { return }
        chunks?.deleteAll(fileId)
        saveLibrary()
        onEvent(.files(library))
        await publishManifest()
    }

    /// Keep a file on every phone (essential), or only where it's opened.
    public func setEssential(_ fileId: String, _ essential: Bool) async {
        guard library.setEssential(fileId, essential, now: now()) else { return }
        saveLibrary()
        onEvent(.files(library))
        await publishManifest()
        await runRepairs()
    }

    /// Our whole view of the library, retained, signed and encrypted under the current key.
    private func publishManifest() async {
        guard case .member(let group) = state, !library.entries.isEmpty,
              let payload = try? FileCodec.sealManifest(library.manifest(groupId: group.groupId, now: now()), group: group, identity: identity)
        else { return }
        transport.publish(topic: Topics.fileManifest(groupId: group.groupId), payload: payload, qos: .qos1, retained: true)
    }

    private func publishChunks(_ file: SharedFile, keyVersion: Int, indices: [Int], group: GroupDefinition) async {
        guard let chunks else { return }
        for i in indices {
            guard let blob = chunks.read(fileId: file.fileId, index: i),
                  let message = try? FileCodec.chunkMessage(fileId: file.fileId, index: i, total: file.chunkCount, ciphertext: blob,
                                                            keyVersion: keyVersion, kid: file.kid) else { continue }
            transport.publish(topic: Topics.fileChunk(groupId: group.groupId, fileId: file.fileId, chunkIndex: i), payload: message, qos: .qos1)
            try? await Task.sleep(nanoseconds: 15_000_000)   // Android's pacing: don't overrun the in-flight window
        }
    }

    /// A member asked for the file list again. The request is plaintext and unauthenticated, so this
    /// only republishes the manifest, at most every 10 minutes per member; chunks come by repair.
    private func answerFileRequest(_ payload: Data) async {
        guard let group = await store.current,
              let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
              let requester = object["requesterId"] as? String, requester != identity.memberId,
              group.containsMember(requester) else { return }
        let t = now()
        if let last = fileRequestSeen[requester], t - last < 10 * 60_000 { return }
        fileRequestSeen[requester] = t
        await publishManifest()
    }

    /// When the manifest was last sent again for a peer who'd never heard of one of our files.
    private var lastManifestResendAtMs: Int64?

    private func saveLibrary() { try? librarySlot.writeJSON(library) }

    // MARK: - Crash alerts (B3)

    /// Raises a crash alert to everyone else in the family and returns its id. It is stored before
    /// the first send, then sent to each member until their phone confirms, for up to 24 hours.
    @discardableResult
    public func raiseEmergencyAlert(_ content: String) async -> String? {
        guard case .member(let group) = state else { return nil }
        let message = ChatCodec.compose(content: content, to: nil, groupId: group.groupId, identity: identity, now: now())
        alerts.add(message, recipients: group.members.map(\.memberId).filter { $0 != identity.memberId })
        saveAlerts()
        chat.insert(message)
        saveChat()
        onEvent(.chat(chat))
        onEvent(.emergency(alerts))
        await flushAlerts(everything: false)
        return message.messageId
    }

    /// Sends every copy that's due (or, on a reconnect, every one still owed). Each resend reuses
    /// the original messageId and timestamp, so receivers keep one copy.
    private func flushAlerts(everything: Bool) async {
        defer { scheduleAlertTimer() }
        guard case .member(let group) = state, transport.isConnected else { return }
        let t = now()
        let due = alerts.due(now: t, everything: everything)
        guard !due.isEmpty else { return }
        for (alert, recipientId) in due {
            guard let peer = group.findMember(byId: recipientId) else {
                alerts.markLeft(alertId: alert.id, recipientId: recipientId)
                continue
            }
            guard let plaintext = try? ChatCodec.plaintext(alert.message),
                  let sealed = await engine.sealForPeer(plaintext, to: peer) else { continue }
            transport.publish(topic: Topics.chat(memberId: peer.memberId), payload: sealed, qos: .qos1)
            alerts.recordAttempt(alertId: alert.id, recipientId: recipientId, now: t)
            // PENDING → SENT only; a receipt may already have moved it further.
            if chat.messages[alert.id]?.status == .pending { chat.setStatus(alert.id, .sent) }
        }
        saveAlerts()
        saveChat()
        onEvent(.emergency(alerts))
        onEvent(.chat(chat))
    }

    private func scheduleAlertTimer() {
        alertTimer?.cancel()
        alertTimer = nil
        guard running, let next = alerts.nextDueAtMs(now: now()) else { return }
        let wait = UInt64(max(next - now(), 0)) * 1_000_000
        alertTimer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: wait)
            guard !Task.isCancelled else { return }
            await self?.flushAlerts(everything: false)
        }
    }

    private func saveAlerts() {
        alerts.prune(now: now())
        try? emergencySlot.writeJSON(alerts)
    }

    private func sendReceipt(_ status: DeliveryStatus, for messageId: String, to memberId: String) async {
        guard let peer = await store.current?.findMember(byId: memberId),
              let body = try? ChatCodec.receipt(for: messageId, status: status, me: identity.memberId, now: now()),
              let sealed = await engine.sealForPeer(body, to: peer) else { return }
        let topic = status == .read ? Topics.chatRead(memberId: memberId) : Topics.chatReceipt(memberId: memberId)
        transport.publish(topic: topic, payload: sealed, qos: .qos1)
    }

    private func saveChat() {
        guard chatReadable else { return }
        try? chatSlot.writeJSON(chat)
    }

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
        library.keepHoldings(of: members)
        history.prune(now: now(), members: members)
        placeWatch.keepOnly(members)
        if let known = knownMembers, !members.subtracting(known).isEmpty { await publishManifest() }
        knownMembers = members
        let before = chat
        chat.prune(now: now(), me: identity.memberId, members: members, groupId: group.groupId)
        if chat != before {
            saveChat()
            onEvent(.chat(chat))
        }
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
