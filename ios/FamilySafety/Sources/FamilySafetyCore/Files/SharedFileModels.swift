import Foundation

// Shared-file wire models (spec §6.7), matching Android SharedFileModels.kt. Android's JSON omits
// any field still at its default, so every defaulted field decodes as Android's default.

/// One entry in the family's file library.
public struct SharedFile: Codable, Equatable, Sendable {
    public let fileId: String
    public let name: String
    public let mimeType: String
    public let sizeBytes: Int64
    /// SHA-256 hex of the plaintext.
    public let contentHash: String
    public let uploaderMemberId: String
    public let uploadedAt: Int64
    public let chunkCount: Int
    public var isDeleted: Bool
    public var deletedByMemberId: String?
    public var deletedAt: Int64?
    /// Absent from older senders, and then true: an older peer replicates everything.
    public var isEssential: Bool
    /// The group key the file's chunks are under (3.3); nil = `fileEncryptionKey`.
    public let kid: String?

    public init(fileId: String, name: String, mimeType: String, sizeBytes: Int64, contentHash: String,
                uploaderMemberId: String, uploadedAt: Int64, chunkCount: Int, isDeleted: Bool = false,
                deletedByMemberId: String? = nil, deletedAt: Int64? = nil, isEssential: Bool = true, kid: String? = nil) {
        self.fileId = fileId
        self.name = name
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.contentHash = contentHash
        self.uploaderMemberId = uploaderMemberId
        self.uploadedAt = uploadedAt
        self.chunkCount = chunkCount
        self.isDeleted = isDeleted
        self.deletedByMemberId = deletedByMemberId
        self.deletedAt = deletedAt
        self.isEssential = isEssential
        self.kid = kid
    }

    private enum CodingKeys: String, CodingKey {
        case fileId, name, mimeType, sizeBytes, contentHash, uploaderMemberId, uploadedAt, chunkCount
        case isDeleted, deletedByMemberId, deletedAt, isEssential, kid
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(fileId: try c.decode(String.self, forKey: .fileId), name: try c.decode(String.self, forKey: .name),
                  mimeType: try c.decode(String.self, forKey: .mimeType), sizeBytes: try c.decode(Int64.self, forKey: .sizeBytes),
                  contentHash: try c.decode(String.self, forKey: .contentHash),
                  uploaderMemberId: try c.decode(String.self, forKey: .uploaderMemberId),
                  uploadedAt: try c.decode(Int64.self, forKey: .uploadedAt), chunkCount: try c.decode(Int.self, forKey: .chunkCount),
                  isDeleted: try c.decodeIfPresent(Bool.self, forKey: .isDeleted) ?? false,
                  deletedByMemberId: try c.decodeIfPresent(String.self, forKey: .deletedByMemberId),
                  deletedAt: try c.decodeIfPresent(Int64.self, forKey: .deletedAt),
                  isEssential: try c.decodeIfPresent(Bool.self, forKey: .isEssential) ?? true,
                  kid: try c.decodeIfPresent(String.self, forKey: .kid))
    }
}

/// The library index, retained on the group manifest topic (inside `EncryptedFileManifest`).
public struct FileManifest: Codable, Equatable, Sendable {
    public let groupId: String
    public let files: [SharedFile]
    /// Epoch ms of the last change.
    public let version: Int64
    public var pad: String

    public init(groupId: String, files: [SharedFile], version: Int64, pad: String = "") {
        self.groupId = groupId
        self.files = files
        self.version = version
        self.pad = pad
    }

    private enum CodingKeys: String, CodingKey { case groupId, files, version, pad }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(groupId: try c.decode(String.self, forKey: .groupId), files: try c.decode([SharedFile].self, forKey: .files),
                  version: try c.decode(Int64.self, forKey: .version), pad: try c.decodeIfPresent(String.self, forKey: .pad) ?? "")
    }
}

/// What is published on the manifest topic when the family has a file key. Signed over the ciphertext.
public struct EncryptedFileManifest: Codable, Equatable, Sendable {
    public let keyVersion: Int
    public let data: String
    /// Absent (older senders) decodes as "" and is refused.
    public let signerMemberId: String
    public let signature: String
    public let kid: String?

    public init(keyVersion: Int, data: String, signerMemberId: String, signature: String, kid: String? = nil) {
        self.keyVersion = keyVersion
        self.data = data
        self.signerMemberId = signerMemberId
        self.signature = signature
        self.kid = kid
    }

    private enum CodingKeys: String, CodingKey { case keyVersion, data, signerMemberId, signature, kid }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(keyVersion: try c.decode(Int.self, forKey: .keyVersion), data: try c.decode(String.self, forKey: .data),
                  signerMemberId: try c.decodeIfPresent(String.self, forKey: .signerMemberId) ?? "",
                  signature: try c.decodeIfPresent(String.self, forKey: .signature) ?? "",
                  kid: try c.decodeIfPresent(String.self, forKey: .kid))
    }

    /// `{keyVersion}|{data}`, or `{keyVersion}|{kid}|{data}` with a kid (3.3).
    public static func signingPayload(keyVersion: Int, data: String, kid: String?) -> String {
        kid.map { "\(keyVersion)|\($0)|\(data)" } ?? "\(keyVersion)|\(data)"
    }
}

/// One chunk, on `familysafe/group/{gid}/files/chunk/{fileId}/{index}`. `data` is AES-GCM `nonce‖ct‖tag`.
public struct FileChunkMessage: Codable, Equatable, Sendable {
    public let fileId: String
    public let chunkIndex: Int
    public let totalChunks: Int
    public let data: String
    /// Absent from older senders, and then 1.
    public let keyVersion: Int
    public let kid: String?

    public init(fileId: String, chunkIndex: Int, totalChunks: Int, data: String, keyVersion: Int, kid: String? = nil) {
        self.fileId = fileId
        self.chunkIndex = chunkIndex
        self.totalChunks = totalChunks
        self.data = data
        self.keyVersion = keyVersion
        self.kid = kid
    }

    private enum CodingKeys: String, CodingKey { case fileId, chunkIndex, totalChunks, data, keyVersion, kid }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(fileId: try c.decode(String.self, forKey: .fileId), chunkIndex: try c.decode(Int.self, forKey: .chunkIndex),
                  totalChunks: try c.decode(Int.self, forKey: .totalChunks), data: try c.decode(String.self, forKey: .data),
                  keyVersion: try c.decodeIfPresent(Int.self, forKey: .keyVersion) ?? FileKeys.legacyVersion,
                  kid: try c.decodeIfPresent(String.self, forKey: .kid))
    }
}

/// The wrapper for file control messages (repair, availability), under the group file key.
public struct EncryptedFileMessage: Codable, Equatable, Sendable {
    public let keyVersion: Int
    public let data: String
    public let kid: String?

    public init(keyVersion: Int, data: String, kid: String? = nil) {
        self.keyVersion = keyVersion
        self.data = data
        self.kid = kid
    }
}

/// "Send me these chunks of this exact file", to one peer's `files/repair`.
public struct ChunkRepairRequest: Codable, Equatable, Sendable {
    public let requestId: String
    public let requesterId: String
    public let fileId: String
    public let contentHash: String
    public let totalChunks: Int
    public let missing: [Int]
    public let timestamp: Int64

    public init(requestId: String, requesterId: String, fileId: String, contentHash: String, totalChunks: Int,
                missing: [Int], timestamp: Int64) {
        self.requestId = requestId
        self.requesterId = requesterId
        self.fileId = fileId
        self.contentHash = contentHash
        self.totalChunks = totalChunks
        self.missing = missing
        self.timestamp = timestamp
    }

    private enum CodingKeys: String, CodingKey { case requestId, requesterId, fileId, contentHash, totalChunks, missing, timestamp }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(requestId: try c.decode(String.self, forKey: .requestId), requesterId: try c.decode(String.self, forKey: .requesterId),
                  fileId: try c.decode(String.self, forKey: .fileId), contentHash: try c.decode(String.self, forKey: .contentHash),
                  totalChunks: try c.decode(Int.self, forKey: .totalChunks), missing: try c.decode([Int].self, forKey: .missing),
                  timestamp: try c.decodeIfPresent(Int64.self, forKey: .timestamp) ?? 0)
    }
}

/// What one device holds, sent to each peer's `files/availability`. A state summary, not an event.
public struct FileHoldingsAnnouncement: Codable, Equatable, Sendable {
    public let announcerId: String
    public let holdings: [FileHolding]
    public let timestamp: Int64

    public init(announcerId: String, holdings: [FileHolding], timestamp: Int64) {
        self.announcerId = announcerId
        self.holdings = holdings
        self.timestamp = timestamp
    }

    private enum CodingKeys: String, CodingKey { case announcerId, holdings, timestamp }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(announcerId: try c.decode(String.self, forKey: .announcerId), holdings: try c.decode([FileHolding].self, forKey: .holdings),
                  timestamp: try c.decodeIfPresent(Int64.self, forKey: .timestamp) ?? 0)
    }
}

public struct FileHolding: Codable, Equatable, Sendable {
    public static let complete = "COMPLETE"
    public static let partial = "PARTIAL"

    public let fileId: String
    public let contentHash: String
    public let state: String
    public let chunksHeld: Int
    public let chunkCount: Int

    public init(fileId: String, contentHash: String, state: String, chunksHeld: Int, chunkCount: Int) {
        self.fileId = fileId
        self.contentHash = contentHash
        self.state = state
        self.chunksHeld = chunksHeld
        self.chunkCount = chunkCount
    }
}
