import CryptoKit
import Foundation

/// Where a shared file's chunks live on this phone: one file per chunk, holding the wire
/// ciphertext verbatim (`nonce ‖ ct ‖ tag`), so repair can re-serve exactly what arrived and
/// nothing plaintext is kept at rest (spec §6.7 "At-rest storage"). Excluded from backup.
public final class ChunkStore: @unchecked Sendable {
    private let root: URL
    private let viewRoot: URL
    private let fm = FileManager.default

    public init(root: URL, viewRoot: URL) {
        self.root = root
        self.viewRoot = viewRoot
    }

    /// File ids come off the network and become directory names: anything but a plain id is refused.
    public static func isSafeId(_ id: String) -> Bool {
        !id.isEmpty && id.count <= 64 && id.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_" }
            && id.unicodeScalars.allSatisfy(\.isASCII)
    }

    private func dir(_ fileId: String) -> URL? {
        Self.isSafeId(fileId) ? root.appendingPathComponent(fileId, isDirectory: true) : nil
    }

    public func write(fileId: String, index: Int, ciphertext: Data) throws {
        guard let d = dir(fileId), index >= 0 else { throw CocoaError(.fileWriteInvalidFileName) }
        try fm.createDirectory(at: d, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var r = root
        try? r.setResourceValues(values)
        try ciphertext.write(to: d.appendingPathComponent("\(index)"), options: .atomic)
    }

    public func read(fileId: String, index: Int) -> Data? {
        dir(fileId).flatMap { try? Data(contentsOf: $0.appendingPathComponent("\(index)")) }
    }

    public func deleteAll(_ fileId: String) {
        if let d = dir(fileId) { try? fm.removeItem(at: d) }
        if Self.isSafeId(fileId) { try? fm.removeItem(at: viewRoot.appendingPathComponent(fileId, isDirectory: true)) }
    }

    /// Decrypts every chunk in order and hashes the result; nil if any is missing or won't open.
    public func verifiedHash(fileId: String, chunkCount: Int, key: SymmetricKey) -> String? {
        var hasher = SHA256()
        for i in 0..<chunkCount {
            guard let blob = read(fileId: fileId, index: i), let plain = try? FileCipher.open(blob, key: key) else { return nil }
            hasher.update(data: plain)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// A temporary plaintext copy for viewing, under a name the viewer can type-detect. Cleared on launch.
    public func materialize(fileId: String, name: String, chunkCount: Int, key: SymmetricKey) throws -> URL {
        guard Self.isSafeId(fileId) else { throw CocoaError(.fileWriteInvalidFileName) }
        let d = viewRoot.appendingPathComponent(fileId, isDirectory: true)
        try fm.createDirectory(at: d, withIntermediateDirectories: true)
        let safeName = name.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        let url = d.appendingPathComponent(safeName.isEmpty || safeName.hasPrefix(".") ? "file" : safeName)
        fm.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        for i in 0..<chunkCount {
            guard let blob = read(fileId: fileId, index: i) else { throw CocoaError(.fileReadNoSuchFile) }
            try handle.write(contentsOf: try FileCipher.open(blob, key: key))
        }
        return url
    }

    public func clearViews() {
        try? fm.removeItem(at: viewRoot)
    }
}
