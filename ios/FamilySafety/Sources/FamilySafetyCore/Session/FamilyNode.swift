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
    private var running = false
    private var backoffSeconds: UInt64 = 2
    private var inbound: AsyncStream<(String, Data)>.Continuation?
    private var bridge: TransportBridge?
    public private(set) var state: NodeState = .noFamily

    public init(profile: LocalProfile, credentials: BrokerCredentials, groupPersistence: GroupStatePersistence,
                inviteSlot: SecretSlot, pendingJoinSlot: SecretSlot, onEvent: @escaping @Sendable (NodeEvent) -> Void) throws {
        self.profile = profile
        self.identity = try profile.identity()
        self.credentials = credentials
        self.store = GroupStateStore(localMemberId: identity.memberId, persistence: groupPersistence)
        self.pendingJoinSlot = pendingJoinSlot
        self.transport = MqttTransport(memberId: identity.memberId, credentials: credentials)
        self.outbox = TransportOutbox(transport: transport)
        self.engine = GroupSyncEngine(identity: identity, store: store, outbox: outbox)
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
        await coordinator?.stop()
        if transport.isConnected, let group = await store.current,
           let offline = try? PresenceCodec.encode(identity: identity, isOnline: false, timestamp: now(), groupFileKeyHex: group.fileEncryptionKey) {
            transport.publish(topic: Topics.presence(memberId: identity.memberId), payload: offline, qos: .qos1, retained: true)
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
        guard running, !transport.isConnected else { return }
        let group = await store.current
        let will: Data
        do {
            will = try PresenceCodec.encode(identity: identity, isOnline: false, timestamp: now(), groupFileKeyHex: group?.fileEncryptionKey)
        } catch { return }
        do {
            try await transport.connect(willPayload: will)
        } catch MqttTransportError.alreadyConnecting {
            return
        } catch {
            scheduleReconnect()
            return
        }
        backoffSeconds = 2
        await resubscribe()
        if let group, let online = try? PresenceCodec.encode(identity: identity, isOnline: true, timestamp: now(), groupFileKeyHex: group.fileEncryptionKey) {
            transport.publish(topic: Topics.presence(memberId: identity.memberId), payload: online, qos: .qos1, retained: true)
            await engine.requestRefresh(reason: "connected")
        }
        transport.flushPendingQueue()
        await coordinator?.start()
    }

    /// The last-will is fixed at connect, so a new family (or a new key) needs a new session.
    private func reconnect() async {
        transport.disconnect()
        await connect()
    }

    private func resubscribe() async {
        let group = await store.current
        let peers = group?.members.map(\.memberId).filter { $0 != identity.memberId } ?? []
        _ = await transport.restoreSubscriptions(groupId: group?.groupId, peerMemberIds: peers)
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
        let (stream, continuation) = AsyncStream<(String, Data)>.makeStream(bufferingPolicy: .bufferingNewest(500))
        inbound = continuation
        let bridge = TransportBridge(continuation: continuation) { [weak self] state in
            Task { await self?.connectionChanged(state) }
        }
        self.bridge = bridge
        transport.delegate = bridge
        Task { [weak self] in
            for await (topic, payload) in stream { await self?.route(topic: topic, payload: payload) }
        }
    }

    private func route(topic: String, payload: Data) async {
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
            if case .prompted = await desk.handle(payload: payload) { await publishPrompts() }
            if let group = await store.current, case .member = state { setState(.member(group)) }
        case Topics.groupSyncInbox(memberId: me):
            let before = await store.current?.members.map(\.memberId)
            let event = await engine.handleGroupSync(payload: payload)
            if case .outcome(.removed) = event { setState(.removed); return }
            if let group = await store.current {
                setState(.member(group))
                if group.members.map(\.memberId) != before { await resubscribe() }
            }
        case Topics.syncRequest(memberId: me):
            _ = await engine.handleRefreshRequest(payload: payload)
        default:
            if let group = await store.current, topic == Topics.groupAck(groupId: group.groupId) {
                await engine.handleAck(payload: payload)
            } else if topic.hasSuffix("/presence"), let group = await store.current {
                let sender = topic.split(separator: "/").dropFirst().first.map(String.init) ?? ""
                guard sender != me, let member = group.findMember(byId: sender),
                      let status = PresenceCodec.decode(payload, sender: member, groupFileKeyHex: group.fileEncryptionKey) else { return }
                if let known = presence[sender], known.timestamp > status.timestamp { return }
                presence[sender] = status
                onEvent(.presence(presence))
            }
        }
    }

    // MARK: - Helpers

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
    private let continuation: AsyncStream<(String, Data)>.Continuation
    private let onState: @Sendable (MqttConnectionState) -> Void

    init(continuation: AsyncStream<(String, Data)>.Continuation, onState: @escaping @Sendable (MqttConnectionState) -> Void) {
        self.continuation = continuation
        self.onState = onState
    }

    func transport(_ transport: MqttTransport, didReceiveMessage payload: Data, onTopic topic: String) {
        continuation.yield((topic, payload))
    }

    func transport(_ transport: MqttTransport, didChangeConnectionState state: MqttConnectionState) {
        onState(state)
    }
}
