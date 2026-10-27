import Foundation

/// The vault on this phone (spec §6.8): the 131072-byte container, the persisted high-water mark of
/// container versions, and the document chunks every phone holds without being able to read them.
/// Everything lives in one directory excluded from backup, so a vault follows the family, not the hardware.
public final class VaultStore: @unchecked Sendable {
    public static let chunkSize = 32 * 1024
    public static let maxItemBytes = 25 * 1024 * 1024
    public static let maxItemChunks = maxItemBytes / chunkSize + 1
    public static let maxBlobs = 256
    public static let maxTotalBytes: Int64 = 250 * 1024 * 1024
    public static let spaceHeadroomBytes: Int64 = 64 * 1024 * 1024
    public static let maxChunksPerRepair = 64

    /// What is held of one document: its chunk count (fixed once seen) and which chunks.
    public struct Blob: Codable, Equatable, Sendable {
        public let chunkCount: Int
        public var held: Set<Int>
        public var isComplete: Bool { held.count == chunkCount }
    }

    private let dir: URL
    private let chunks: ChunkStore
    private let lock = NSLock()
    private let fm = FileManager.default
    private var blobs: [String: Blob]

    public init(directory: URL, viewRoot: URL) {
        dir = directory
        chunks = ChunkStore(root: directory.appendingPathComponent("blobs", isDirectory: true), viewRoot: viewRoot)
        blobs = (try? JSONDecoder().decode([String: Blob].self, from: Data(contentsOf: directory.appendingPathComponent("blobs.json")))) ?? [:]
    }

    private var containerURL: URL { dir.appendingPathComponent("container.bin") }
    private var versionURL: URL { dir.appendingPathComponent("container.bin.ver") }

    private func ensureDir() throws {
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var d = dir
        try? d.setResourceValues(values)
    }

    /// The container, created full of random bytes the first time on every phone, whether or not anyone
    /// ever uses a vault. A file of the wrong length is set aside, never deleted; a read that fails
    /// throws rather than handing back fresh bytes the next write would publish over the family's.
    public func container() throws -> Data {
        lock.lock(); defer { lock.unlock() }
        return try containerLocked()
    }

    private func containerLocked() throws -> Data {
        if fm.fileExists(atPath: containerURL.path) {
            let data = try Data(contentsOf: containerURL)
            if data.count == VaultCrypto.containerBytes { return data }
            try? fm.moveItem(at: containerURL, to: dir.appendingPathComponent("container.bin.corrupt"))
        }
        let fresh = VaultCrypto.randomContainer()
        try writeLocked(fresh)
        return fresh
    }

    /// Atomic replace, never delete-then-write (F31).
    public func write(_ container: Data) throws {
        lock.lock(); defer { lock.unlock() }
        try writeLocked(container)
    }

    private func writeLocked(_ container: Data) throws {
        precondition(container.count == VaultCrypto.containerBytes)
        try ensureDir()
        #if os(iOS)
        try container.write(to: containerURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try container.write(to: containerURL, options: .atomic)
        #endif
    }

    /// Applies a change to the current container under the lock; nil when the change declined.
    public func update(_ change: (Data) -> Data?) throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        guard let next = change(try containerLocked()) else { return nil }
        try writeLocked(next)
        return next
    }

    /// The highest container version seen, persisted beside (not inside) the container (F8).
    public var version: Int64 {
        lock.lock(); defer { lock.unlock() }
        return (try? String(contentsOf: versionURL, encoding: .utf8)).flatMap { Int64($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? 0
    }

    public func setVersion(_ v: Int64) {
        lock.lock(); defer { lock.unlock() }
        try? ensureDir()
        try? String(v).write(to: versionURL, atomically: true, encoding: .utf8)
    }

    // MARK: Document chunks

    public func blob(_ fileId: String) -> Blob? {
        lock.lock(); defer { lock.unlock() }
        return blobs[fileId]
    }

    /// Stores a chunk without authenticating it (this phone very likely can't read it), within the
    /// bounds that keep any peer from filling the phone. Refuses, never truncates.
    @discardableResult
    public func storeChunk(fileId: String, index: Int, totalChunks: Int, ciphertext: Data) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard ChunkStore.isSafeId(fileId), (1...Self.maxItemChunks).contains(totalChunks), (0..<totalChunks).contains(index),
              ciphertext.count <= Self.chunkSize + 64 else { return false }
        if let b = blobs[fileId] {
            guard b.chunkCount == totalChunks, !b.isComplete, !b.held.contains(index) else { return false }
        } else {
            guard blobs.count < Self.maxBlobs else { return false }
            let heldChunks = blobs.values.reduce(0) { $0 + $1.chunkCount }
            guard Int64(heldChunks + totalChunks) * Int64(Self.chunkSize) <= Self.maxTotalBytes else { return false }
            if let free = freeSpace(), free < Int64(totalChunks * Self.chunkSize) + Self.spaceHeadroomBytes { return false }
        }
        guard (try? chunks.write(fileId: fileId, index: index, ciphertext: ciphertext)) != nil else { return false }
        var b = blobs[fileId] ?? Blob(chunkCount: totalChunks, held: [])
        b.held.insert(index)
        blobs[fileId] = b
        saveBlobsLocked()
        return true
    }

    /// A document this phone just added: every chunk is held.
    public func addOwn(fileId: String, chunkCount: Int) {
        lock.lock(); defer { lock.unlock() }
        blobs[fileId] = Blob(chunkCount: chunkCount, held: Set(0..<chunkCount))
        saveBlobsLocked()
    }

    public func writeOwnChunk(fileId: String, index: Int, ciphertext: Data) throws {
        try chunks.write(fileId: fileId, index: index, ciphertext: ciphertext)
    }

    public func discard(_ fileId: String) {
        lock.lock(); defer { lock.unlock() }
        chunks.deleteAll(fileId)
        blobs[fileId] = nil
        saveBlobsLocked()
    }

    public func readChunk(fileId: String, index: Int) -> Data? { chunks.read(fileId: fileId, index: index) }

    public func missing(_ fileId: String, chunkCount: Int, limit: Int = 256) -> [Int] {
        let held = blob(fileId)?.held ?? []
        return Array((0..<chunkCount).lazy.filter { !held.contains($0) }.prefix(limit))
    }

    /// Chunks to serve a repair request from: whatever is held, at most 64.
    public func servable(_ r: VaultChunkRequest) -> (chunkCount: Int, indices: [Int])? {
        guard let b = blob(r.fileId) else { return nil }
        let wanted = Array(Set(r.missing).filter { b.held.contains($0) }.sorted().prefix(Self.maxChunksPerRepair))
        return wanted.isEmpty ? nil : (b.chunkCount, wanted)
    }

    public func verifiedHash(fileId: String, chunkCount: Int, key: SymmetricKeyBox) -> String? {
        chunks.verifiedHash(fileId: fileId, chunkCount: chunkCount, key: key.key)
    }

    public func materialize(fileId: String, name: String, chunkCount: Int, key: SymmetricKeyBox) throws -> URL {
        try chunks.materialize(fileId: fileId, name: name, chunkCount: chunkCount, key: key.key)
    }

    /// Everything decrypted for viewing goes: at launch and whenever a vault closes.
    public func forgetViews() { chunks.clearViews() }

    private func saveBlobsLocked() {
        try? ensureDir()
        try? JSONEncoder().encode(blobs).write(to: dir.appendingPathComponent("blobs.json"), options: .atomic)
    }

    private func freeSpace() -> Int64? {
        (try? dir.deletingLastPathComponent().resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?
            .volumeAvailableCapacityForImportantUsage
    }
}
