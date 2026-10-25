import UIKit
import CoreLocation
import FamilySafetyCore
import Foundation
import SwiftUI

/// The app's one source of UI state. Owns the local profile (recovery phrase + name) and the
/// `FamilyNode`; screens read its published values and call its actions.
@MainActor
final class AppModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        /// No identity yet: onboarding.
        case welcome
        /// Saved data can't be read right now. Nothing has been deleted (F14).
        case storageUnavailable
        case missingBrokerConfig
        case running
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var profile: LocalProfile?
    @Published private(set) var nodeState: NodeState = .noFamily
    @Published private(set) var connection: MqttConnectionState = .disconnected
    @Published private(set) var prompts: [JoinPrompt] = []
    @Published private(set) var presence: [String: PresenceStatus] = [:]
    /// Members whose app is too old to follow the family (§6.2 rule 3).
    @Published private(set) var needsUpdate: Set<String> = []
    /// Refusals that may be someone outside the family trying to get in (phase 1).
    @Published private(set) var securityLog = SecurityLog()
    /// The newest known location per member, ours included (B2).
    @Published private(set) var locations: [String: LocationUpdate] = [:]
    /// "Share my location" is off.
    @Published private(set) var sharingPaused = false
    /// Every chat message on this phone (B3).
    @Published private(set) var chat = ChatBook()
    /// Crash alerts this phone raised, with each member's copy (B3).
    @Published private(set) var emergency = EmergencyOutbox()
    /// The family's shared files as this phone sees them (B5).
    @Published private(set) var files = FileLibrary()
    @Published private(set) var locationAuthorization: CLAuthorizationStatus = .notDetermined
    private let locationProvider = LocationProvider()
    private var isActive = false
    @Published var lastError: String?
    /// "Signed in on another phone" (2.2). Shown as its own alert.
    @Published var identityNotice: String?

    private var node: FamilyNode?
    private let profileSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "profile")
    private let inviteSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "invite-book")
    private let pendingJoinSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "pending-join")
    private let deviceSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "device-id")
    private let ledgerSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "protocol-ledger")
    private let presenceSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "presence-book")
    private let securitySlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "security-log")
    private let locationSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "location-state")

    var memberId: String? { try? profile?.identity().memberId }

    func load() {
        guard phase == .loading || phase == .storageUnavailable else { return }
        do {
            guard let saved = try profileSlot.readJSON(LocalProfile.self) else {
                phase = .welcome
                #if DEBUG
                TestDriver.prepareProfile(self)
                #endif
                return
            }
            try startNode(saved)
        } catch {
            #if DEBUG
            print("[app] load failed: \(error)")
            #endif
            phase = .storageUnavailable
        }
    }

    /// Saves a new or restored identity, then starts the family connection.
    func saveProfile(mnemonic: String, displayName: String) throws {
        let profile = LocalProfile(mnemonic: mnemonic, displayName: displayName)
        _ = try profile.identity()
        try profileSlot.writeJSON(profile)
        try startNode(profile)
    }

    /// The security report as plain text, for the share sheet. Nil without a running node.
    func securityReport() async -> String? {
        guard let node else { return nil }
        let info = Bundle.main.infoDictionary
        let version = "\(info?["CFBundleShortVersionString"] as? String ?? "?") (\(info?["CFBundleVersion"] as? String ?? "?"))"
        return await node.securityReport(appVersion: version)
    }

    func recoveryPhrase() -> [String] {
        profile?.mnemonic.split(separator: " ").map(String.init) ?? []
    }

    func setActive(_ active: Bool) {
        isActive = active
        updateLocationUpdates()
        guard let node else { return }
        Task { active ? await node.start() : await node.stop() }
    }

    func setSharingPaused(_ paused: Bool) async {
        _ = await run { await $0.setSharingPaused(paused) }
    }

    /// Open: continuous updates while in a family and not paused. In the background: wake-ups
    /// around the last shared fix (with Always permission), decided from saved state because a
    /// background relaunch hasn't loaded the family yet. Nothing restarts while paused (spec §11).
    private func updateLocationUpdates() {
        guard !sharingPaused, let id = memberId else { locationProvider.stop(); return }
        if isActive {
            if case .member = nodeState { locationProvider.start(memberId: id) } else { locationProvider.stop() }
        } else if let last = node?.savedLastFix {
            locationProvider.enterBackground(memberId: id, lastFix: last)
        } else {
            locationProvider.stop()
        }
    }

    /// From the user's tap in Settings: ask for Always, or send them to iPhone Settings if the
    /// system won't ask again.
    func allowBackgroundLocation() {
        if locationAuthorization == .authorizedWhenInUse || locationAuthorization == .notDetermined {
            locationProvider.requestAlways()
        } else if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    func createFamily(name: String) async {
        await run { try await $0.createFamily(name: name) }
    }

    func join(code: String) async -> Bool {
        await run { try await $0.join(code: code) }
    }

    func cancelJoin() async {
        await node?.cancelJoin()
    }

    func issueInvite() async -> String? {
        guard let node else { return nil }
        do { return try await node.issueInvite() } catch { lastError = Self.message(error); return nil }
    }

    func rename(_ name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let profile else { return }
        guard await run({ try await $0.updateProfile(displayName: trimmed) }) else { return }
        let renamed = LocalProfile(mnemonic: profile.mnemonic, displayName: trimmed)
        try? profileSlot.writeJSON(renamed)
        self.profile = renamed
    }

    func setColor(_ colour: PersonColor) async {
        await run { try await $0.updateProfile(colorHue: .some(colour.storedHue)) }
    }

    func sendChat(_ text: String, to memberId: String?) async {
        await node?.sendChat(text, to: memberId)
    }

    /// The conversation on screen (nil when none): its messages are marked read, and the senders told.
    func openConversation(_ conversationId: String?) async {
        await node?.openConversation(conversationId)
    }

    /// Sends a crash alert to everyone else, worded as Android words it so both apps' families
    /// read the same thing, with this phone's last position when there is one.
    func raiseCrashAlert() async {
        let name = profile?.displayName ?? "A family member"
        let place = chatMemberId.flatMap { locations[$0] }.map { " https://maps.google.com/?q=\($0.latitude),\($0.longitude)" } ?? ""
        await node?.raiseEmergencyAlert("🚨 \(name) may have been in an accident and did not respond.\(place)")
    }

    /// Shares a file picked from Files with the family. The picker hands over a security-scoped URL.
    func uploadFile(_ url: URL) async {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let type = (try? url.resourceValues(forKeys: [.contentTypeKey]))?.contentType
        await run { try await $0.uploadFile(at: url, name: url.lastPathComponent,
                                            mimeType: type?.preferredMIMEType ?? "application/octet-stream") }
    }

    func deleteFile(_ fileId: String) async { await node?.deleteFile(fileId) }
    func setEssential(_ fileId: String, _ essential: Bool) async { await node?.setEssential(fileId, essential) }

    /// One member's positions on one local day, oldest first.
    func history(of memberId: String, day: Date) async -> [LocationUpdate] {
        let start = Calendar.current.startOfDay(for: day)
        let end = Calendar.current.date(byAdding: .day, value: 1, to: start) ?? start
        return await node?.history(of: memberId, from: Int64(start.timeIntervalSince1970 * 1000),
                                   to: Int64(end.timeIntervalSince1970 * 1000)) ?? []
    }

    /// A file that isn't downloaded automatically: fetch it.
    func requestFile(_ fileId: String) async { await node?.requestFile(fileId) }

    /// A temporary plaintext copy of a downloaded file, for viewing.
    func openFile(_ fileId: String) async -> URL? { await node?.openFile(fileId) }

    /// This phone's member id, derived once when the node starts (deriving it is slow).
    private(set) var chatMemberId: String?

    func leaveFamily() async { await run { try await $0.leave() } }
    func removeMember(_ memberId: String) async { await run { try await $0.removeMember(memberId) } }

    func approve(_ prompt: JoinPrompt) async { await run { try await $0.approve(requestId: prompt.requestId) } }
    func reject(_ prompt: JoinPrompt) async { await run { try await $0.reject(requestId: prompt.requestId) } }

    // MARK: - Internals

    private func startNode(_ profile: LocalProfile) throws {
        guard let credentials = BrokerSecrets.load() else { phase = .missingBrokerConfig; return }
        self.profile = profile
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let persistence = FileGroupStatePersistence(fileURL: directory.appendingPathComponent("group.state"),
                                                    keyProvider: KeychainGroupStateKeyProvider())
        let node = try FamilyNode(profile: profile, credentials: credentials, groupPersistence: persistence,
                                  inviteSlot: inviteSlot, pendingJoinSlot: pendingJoinSlot, deviceSlot: deviceSlot,
                                  ledgerSlot: ledgerSlot, presenceSlot: presenceSlot, securitySlot: securitySlot,
                                  locationSlot: locationSlot,
                                  chatSlot: SealedFileSlot(url: directory.appendingPathComponent("chat.sealed"),
                                                           keySlot: KeychainSlot(service: "jibaro.familysafety", account: "chat-key")),
                                  emergencySlot: KeychainSlot(service: "jibaro.familysafety", account: "emergency-outbox"),
                                  librarySlot: SealedFileSlot(url: directory.appendingPathComponent("files.sealed"),
                                                              keySlot: KeychainSlot(service: "jibaro.familysafety", account: "files-key")),
                                  historySlot: SealedFileSlot(url: directory.appendingPathComponent("history.sealed"),
                                                              keySlot: KeychainSlot(service: "jibaro.familysafety", account: "history-key")),
                                  chunkStore: ChunkStore(root: directory.appendingPathComponent("files", isDirectory: true),
                                                         viewRoot: FileManager.default.temporaryDirectory.appendingPathComponent("file-views", isDirectory: true))) { [weak self] event in
            Task { @MainActor in self?.handle(event) }
        }
        self.node = node
        chatMemberId = node.memberId
        securityLog = node.securityLog
        sharingPaused = node.sharingPausedAtLaunch
        locationAuthorization = locationProvider.authorization
        locationProvider.onFix = { [weak self] fix in
            guard let node = self?.node else { return }
            Task { await node.shareLocation(fix) }
        }
        locationProvider.onAuthorization = { [weak self] status in self?.locationAuthorization = status }
        locationProvider.onBackgroundFix = { [weak self] fix in
            guard let node = self?.node else { return }
            // ~10 s of background time to connect, send and disconnect (spec §11).
            var task = UIBackgroundTaskIdentifier.invalid
            task = UIApplication.shared.beginBackgroundTask(withName: "share-location") {
                UIApplication.shared.endBackgroundTask(task)
            }
            Task {
                await node.shareFromBackground(fix)
                UIApplication.shared.endBackgroundTask(task)
            }
        }
        // Launched into the background by a location wake-up: re-arm before anything else.
        updateLocationUpdates()
        phase = .running
        Task { await node.start() }
    }

    private func handle(_ event: NodeEvent) {
        switch event {
        case .state(let state):
            if state == .storageUnavailable { phase = .storageUnavailable }
            nodeState = state
            updateLocationUpdates()
            #if DEBUG
            TestDriver.observe(self, state: state)
            #endif
        case .connection(let state):
            connection = state
            #if DEBUG
            TestDriver.log("connection \(state)")
            #endif
        case .prompts(let list):
            prompts = list
            #if DEBUG
            TestDriver.observe(self, prompts: list)
            #endif
        case .presence(let map): presence = map
        case .needsUpdate(let members): needsUpdate = members
        case .securityLog(let log): securityLog = log
        case .locations(let map): locations = map
        case .chat(let book): chat = book
        case .emergency(let box): emergency = box
        case .files(let library): files = library
        case .sharingPaused(let paused):
            sharingPaused = paused
            updateLocationUpdates()
        case .identityAlert(let text):
            identityNotice = text
            #if DEBUG
            TestDriver.log("ALERT \(text)")
            #endif
        }
    }

    @discardableResult
    private func run(_ action: (FamilyNode) async throws -> Void) async -> Bool {
        guard let node else { return false }
        do { try await action(node); return true } catch { lastError = Self.message(error); return false }
    }

    static func message(_ error: Error) -> String {
        switch error {
        case NodeError.invalidCode: return "That isn't an invite code. Ask for a new one and paste all of it."
        case NodeError.alreadyInFamily: return "This phone is already in a family."
        case NodeError.notInFamily: return "Join or create a family first."
        case NodeError.storageUnavailable: return "This phone's storage can't be used right now. Try again in a moment."
        case NodeError.storageFull: return "The family's files would go over 500 MB. Delete something first."
        case NodeError.fileUnreadable: return "That file couldn't be read, or there isn't room for it on this phone."
        case JoinV2Error.invalidInvite(let reason): return reason
        case JoinDeskError.keyConflict: return "Someone else is using that person's name with different keys. Nothing was approved."
        default: return "Something went wrong: \(error.localizedDescription)"
        }
    }
}
