import Foundation

/// This phone's view of the family's file library (spec §6.7), as Android's SharedFileRepository
/// keeps it: every entry it has seen (tombstones included), which chunks it holds of each, when to
/// next ask for the rest, and who else holds a complete copy. Pure; the node does the I/O.
public struct FileLibrary: Codable, Equatable, Sendable {
    public static let chunkSize = 32 * 1024
    public static let maxMissingPerRequest = 256
    public static let maxChunksPerRepairResponse = 64
    public static let repairBaseBackoffMs: Int64 = 30_000
    public static let repairMaxBackoffMs: Int64 = 30 * 60_000
    public static let announceDebounceMs: Int64 = 30_000
    public static let tombstoneRetentionMs: Int64 = 90 * 24 * 60 * 60 * 1000

    public enum State: String, Codable, Sendable {
        /// Listed, not being fetched (a non-essential file nobody opened).
        case notDownloaded
        case downloading
        /// Every chunk held and the whole verified against `contentHash`.
        case complete
    }

    public struct Entry: Codable, Equatable, Sendable, Identifiable {
        public internal(set) var file: SharedFile
        public internal(set) var state: State
        /// Indices of chunks held (ciphertext stored).
        public internal(set) var held: Set<Int>
        /// The key version every stored chunk is under; 0 until the first one. Slots can't mix versions.
        public internal(set) var blobKeyVersion: Int
        /// Fetch even if not essential (someone opened it).
        public internal(set) var wanted: Bool
        public internal(set) var attempts: Int
        public internal(set) var nextAttemptAtMs: Int64?
        public internal(set) var lastPeerId: String?
        public var id: String { file.fileId }
        public var chunksHeld: Int { held.count }

        var shouldFetch: Bool { !file.isDeleted && state != .complete && (file.isEssential || wanted) }
    }

    public private(set) var entries: [String: Entry] = [:]
    /// memberId → fileId → what they announced.
    public private(set) var holdings: [String: [String: FileHolding]] = [:]
    public private(set) var lastAnnouncedAtMs: Int64?

    public init() {}

    public var visible: [Entry] {
        entries.values.filter { !$0.file.isDeleted }.sorted { $0.file.uploadedAt > $1.file.uploadedAt }
    }

    // MARK: Manifest

    public struct ManifestOutcome: Equatable, Sendable {
        /// Files deleted elsewhere: erase their stored chunks and any view copy.
        public var erase: [String] = []
        /// Files to start (or keep) fetching now.
        public var fetch: [String] = []
        public var changed = false
    }

    /// Merges a received manifest. Absence means nothing (a publisher may not know a file);
    /// a deletion is a present, flagged entry and always wins.
    public mutating func apply(_ manifest: FileManifest, groupId: String, now: Int64) -> ManifestOutcome {
        var out = ManifestOutcome()
        guard manifest.groupId == groupId else { return out }
        // Ids become directory names; counts bound what a manifest can make us track.
        for f in manifest.files where ChunkStore.isSafeId(f.fileId) && f.chunkCount >= 0 && f.chunkCount <= 1_000_000 {
            guard var e = entries[f.fileId] else {
                // New: record it, tombstones too, so a slower peer's copy can't bring it back.
                let entry = Entry(file: f, state: f.isDeleted ? .notDownloaded : (f.isEssential ? .downloading : .notDownloaded),
                                  held: [], blobKeyVersion: 0, wanted: false, attempts: 0,
                                  nextAttemptAtMs: f.isDeleted || !f.isEssential ? nil : now, lastPeerId: nil)
                entries[f.fileId] = entry
                if entry.shouldFetch { out.fetch.append(f.fileId) }
                out.changed = true
                continue
            }
            if f.isDeleted && !e.file.isDeleted {
                e.file.isDeleted = true
                e.file.deletedByMemberId = f.deletedByMemberId
                e.file.deletedAt = f.deletedAt ?? now
                e.held = []
                e.state = .notDownloaded
                e.nextAttemptAtMs = nil
                entries[f.fileId] = e
                for m in holdings.keys { holdings[m]?[f.fileId] = nil }
                out.erase.append(f.fileId)
                out.changed = true
            } else if !e.file.isDeleted, e.state != .complete, e.file.isEssential != f.isEssential {
                // The file's identity (name, hash, kid, size) is fixed when first seen; only the pin moves.
                e.file.isEssential = f.isEssential
                if e.shouldFetch, e.nextAttemptAtMs == nil { e.nextAttemptAtMs = now; e.state = .downloading }
                entries[f.fileId] = e
                if e.shouldFetch { out.fetch.append(f.fileId) }
                out.changed = true
            }
        }
        return out
    }

    /// The manifest this phone would publish: live files plus tombstones within 90 days.
    public func manifest(groupId: String, now: Int64) -> FileManifest {
        let files = entries.values.map(\.file)
            .filter { !$0.isDeleted || now - ($0.deletedAt ?? now) < Self.tombstoneRetentionMs }
            .sorted { $0.fileId < $1.fileId }
        return FileManifest(groupId: groupId, files: files, version: now)
    }

    // MARK: Our own changes

    /// The group storage cap (spec §6.7).
    public static let maxTotalBytes: Int64 = 500 * 1024 * 1024

    public var usedBytes: Int64 { entries.values.filter { !$0.file.isDeleted }.reduce(0) { $0 + $1.file.sizeBytes } }

    /// A live file with exactly these bytes is already in the library.
    public func holdsCopy(ofHash hash: String) -> Bool {
        entries.values.contains { !$0.file.isDeleted && $0.file.contentHash.lowercased() == hash.lowercased() }
    }

    /// A file this phone just uploaded: it holds every chunk, so it is complete from the start.
    public mutating func addOwn(_ file: SharedFile, keyVersion: Int) {
        entries[file.fileId] = Entry(file: file, state: .complete, held: Set(0..<file.chunkCount), blobKeyVersion: keyVersion,
                                     wanted: false, attempts: 0, nextAttemptAtMs: nil, lastPeerId: nil)
    }

    /// Deleted here for the whole family: the tombstone in our next manifest deletes it everywhere.
    @discardableResult
    public mutating func delete(_ fileId: String, by memberId: String, now: Int64) -> Bool {
        guard var e = entries[fileId], !e.file.isDeleted else { return false }
        e.file.isDeleted = true
        e.file.deletedByMemberId = memberId
        e.file.deletedAt = now
        e.held = []
        e.state = .notDownloaded
        e.nextAttemptAtMs = nil
        entries[fileId] = e
        for m in holdings.keys { holdings[m]?[fileId] = nil }
        return true
    }

    /// Pinned (kept on every phone) or not. Anyone may change it; the next manifest carries it.
    @discardableResult
    public mutating func setEssential(_ fileId: String, _ essential: Bool, now: Int64) -> Bool {
        guard var e = entries[fileId], !e.file.isDeleted, e.file.isEssential != essential else { return false }
        e.file.isEssential = essential
        if e.shouldFetch { e.state = .downloading; e.attempts = 0; e.nextAttemptAtMs = now }
        else if e.state != .complete { e.nextAttemptAtMs = nil; if e.held.isEmpty { e.state = .notDownloaded } }
        entries[fileId] = e
        return true
    }

    // MARK: Chunks

    public enum ChunkOutcome: Equatable, Sendable {
        case stored(complete: Bool)
        case ignored(String)
    }

    /// Records an authenticated chunk the node has just stored. Refuses what can't be mixed in.
    public mutating func recordChunk(fileId: String, index: Int, keyVersion: Int, now: Int64) -> ChunkOutcome {
        guard var e = entries[fileId] else { return .ignored("before its manifest") }
        guard !e.file.isDeleted else { return .ignored("deleted") }
        guard e.state != .complete else { return .ignored("already complete") }
        guard e.blobKeyVersion == 0 || e.blobKeyVersion == keyVersion else { return .ignored("other key version") }
        guard !e.held.contains(index) else { return .ignored("duplicate") }
        e.blobKeyVersion = keyVersion
        e.held.insert(index)
        if e.state == .notDownloaded { e.state = .downloading }
        // Progress: ask again soon rather than waiting out a backoff.
        e.attempts = 0
        if e.shouldFetch { e.nextAttemptAtMs = now + 2_000 }
        entries[fileId] = e
        return .stored(complete: e.held.count == e.file.chunkCount)
    }

    /// Whether a chunk for this file is worth checking at all.
    public func accepts(fileId: String) -> Bool {
        guard let e = entries[fileId] else { return false }
        return !e.file.isDeleted && e.state != .complete
    }

    public mutating func markComplete(_ fileId: String) {
        entries[fileId]?.state = .complete
        entries[fileId]?.nextAttemptAtMs = nil
        entries[fileId]?.attempts = 0
    }

    /// The whole didn't hash to `contentHash`: every chunk authenticated, so the bytes are a
    /// different version. Start again.
    public mutating func discard(_ fileId: String, now: Int64) {
        entries[fileId]?.held = []
        entries[fileId]?.blobKeyVersion = 0
        entries[fileId]?.state = .downloading
        entries[fileId]?.nextAttemptAtMs = now
    }

    public func missing(_ fileId: String, limit: Int = maxMissingPerRequest) -> [Int] {
        guard let e = entries[fileId], e.state != .complete else { return [] }
        return Array((0..<e.file.chunkCount).lazy.filter { !e.held.contains($0) }.prefix(limit))
    }

    // MARK: Fetching and repair

    /// Someone opened a non-essential file: fetch it now.
    public mutating func want(_ fileId: String, now: Int64) {
        guard var e = entries[fileId], !e.file.isDeleted, e.state != .complete else { return }
        e.wanted = true
        e.state = .downloading
        e.attempts = 0
        e.nextAttemptAtMs = now
        entries[fileId] = e
    }

    public func due(now: Int64) -> [String] {
        entries.values.filter { $0.shouldFetch && ($0.nextAttemptAtMs ?? .max) <= now }
            .sorted { ($0.nextAttemptAtMs ?? 0) < ($1.nextAttemptAtMs ?? 0) }.prefix(10).map(\.file.fileId)
    }

    public func nextDueAtMs() -> Int64? {
        entries.values.filter(\.shouldFetch).compactMap(\.nextAttemptAtMs).min()
    }

    /// One peer to ask for what's missing: those who announced a complete copy with the same hash,
    /// else everyone, rotating after whoever was asked last.
    public mutating func repairRequest(_ fileId: String, me: String, peers: [String], now: Int64,
                                       requestId: String = UUID().uuidString.lowercased()) -> (peerId: String, request: ChunkRepairRequest)? {
        guard var e = entries[fileId], e.shouldFetch else { return nil }
        let missing = missing(fileId)
        guard !missing.isEmpty, !peers.isEmpty else { return nil }
        let holders = peers.filter { holdings[$0]?[fileId].map { $0.state == FileHolding.complete && $0.contentHash == e.file.contentHash } == true }
        let pool = holders.isEmpty ? peers : holders
        let start = e.lastPeerId.flatMap { pool.firstIndex(of: $0) }.map { ($0 + 1) % pool.count } ?? 0
        let peer = pool[start]
        e.attempts += 1
        e.lastPeerId = peer
        e.nextAttemptAtMs = now + Self.backoff(attempt: e.attempts)
        entries[fileId] = e
        return (peer, ChunkRepairRequest(requestId: requestId, requesterId: me, fileId: fileId, contentHash: e.file.contentHash,
                                         totalChunks: e.file.chunkCount, missing: missing, timestamp: now))
    }

    /// 30 s doubling to a 30-minute ceiling.
    public static func backoff(attempt: Int) -> Int64 {
        min(repairBaseBackoffMs << Int64(min(max(attempt - 1, 0), 6)), repairMaxBackoffMs)
    }

    /// Which chunks to serve a peer's repair request from our complete copy, or nil to refuse.
    public func serve(_ r: ChunkRepairRequest, from peerId: String) -> (file: SharedFile, keyVersion: Int, indices: [Int])? {
        guard r.requesterId == peerId, let e = entries[r.fileId], !e.file.isDeleted, e.state == .complete,
              e.file.contentHash == r.contentHash else { return nil }
        let wanted = Array(Set(r.missing.filter { $0 >= 0 && $0 < e.file.chunkCount }).sorted().prefix(Self.maxChunksPerRepairResponse))
        return wanted.isEmpty ? nil : (e.file, e.blobKeyVersion, wanted)
    }

    // MARK: Availability

    public mutating func record(_ a: FileHoldingsAnnouncement, from peerId: String, members: Set<String>) {
        guard a.announcerId == peerId, members.contains(peerId) else { return }
        holdings[peerId] = Dictionary(a.holdings.map { ($0.fileId, $0) }, uniquingKeysWith: { _, b in b })
    }

    public mutating func keepHoldings(of members: Set<String>) {
        holdings = holdings.filter { members.contains($0.key) }
    }

    public static let manifestResendMinAgeMs: Int64 = 2 * 60_000
    public static let manifestResendSpacingMs: Int64 = 10 * 60_000

    /// Files we shared that are complete here, over 2 minutes old, and not listed at all in a
    /// peer's holdings: they never got the manifest naming them. Only our own uploads (our
    /// manifest is what names them). One listed as PARTIAL is known; its repair fetches it.
    public func sharedButUnknown(to a: FileHoldingsAnnouncement, me: String, now: Int64) -> [String] {
        let listed = Set(a.holdings.map(\.fileId))
        return entries.values.filter {
            $0.file.uploaderMemberId == me && !$0.file.isDeleted && $0.state == .complete &&
                now - $0.file.uploadedAt >= Self.manifestResendMinAgeMs && !listed.contains($0.file.fileId)
        }.map(\.file.fileId).sorted()
    }

    /// Our own holdings, or nil while debounced. Sent even when empty (since 1.15.8), so a peer
    /// can tell we never heard of a file it shared.
    public mutating func announcement(me: String, now: Int64, force: Bool = false) -> FileHoldingsAnnouncement? {
        if !force, let last = lastAnnouncedAtMs, now - last < Self.announceDebounceMs { return nil }
        let held = entries.values.filter { !$0.file.isDeleted && $0.file.chunkCount > 0 && !$0.held.isEmpty }
            .sorted { $0.file.fileId < $1.file.fileId }
            .map { FileHolding(fileId: $0.file.fileId, contentHash: $0.file.contentHash,
                               state: $0.state == .complete ? FileHolding.complete : FileHolding.partial,
                               chunksHeld: $0.chunksHeld, chunkCount: $0.file.chunkCount) }
        lastAnnouncedAtMs = now
        return FileHoldingsAnnouncement(announcerId: me, holdings: held, timestamp: now)
    }

    /// How many other members announced a complete copy of the same bytes; nil until anyone has
    /// announced anything (absence of an announcement is not absence of a copy).
    public func copiesElsewhere(_ fileId: String) -> Int? {
        guard !holdings.isEmpty, let e = entries[fileId] else { return nil }
        return holdings.values.filter { $0[fileId].map { $0.state == FileHolding.complete && $0.contentHash == e.file.contentHash } == true }.count
    }
}
