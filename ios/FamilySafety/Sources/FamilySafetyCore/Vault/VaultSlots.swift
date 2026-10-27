import CryptoKit
import Foundation

// The family vault (spec §6.8), ported from Android's vault package. Every assertion here is
// about something *not* being observable: there is no wrong code, no setup state, no early exit.

/// What is sealed into a slot. Never on the wire as itself.
public struct VaultSlotPayload: Codable, Equatable, Sendable {
    public static let active = "ACTIVE"
    public static let reclaimed = "RECLAIMED"

    public let fileId: String
    public var name: String
    public let mimeType: String
    public let sizeBytes: Int64
    public let contentHash: String
    public let contentKeyHex: String
    public let chunkCount: Int
    public let addedAt: Int64
    public let addedBy: String
    public var state: String
    public var revision: Int64
    public var pad: String

    public init(fileId: String, name: String, mimeType: String, sizeBytes: Int64, contentHash: String, contentKeyHex: String,
                chunkCount: Int, addedAt: Int64, addedBy: String, state: String = VaultSlotPayload.active, revision: Int64 = 0, pad: String = "") {
        self.fileId = fileId
        self.name = name
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.contentHash = contentHash
        self.contentKeyHex = contentKeyHex
        self.chunkCount = chunkCount
        self.addedAt = addedAt
        self.addedBy = addedBy
        self.state = state
        self.revision = revision
        self.pad = pad
    }

    private enum CodingKeys: String, CodingKey {
        case fileId, name, mimeType, sizeBytes, contentHash, contentKeyHex, chunkCount, addedAt, addedBy, state, revision, pad
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(fileId: try c.decode(String.self, forKey: .fileId), name: try c.decode(String.self, forKey: .name),
                  mimeType: try c.decode(String.self, forKey: .mimeType), sizeBytes: try c.decode(Int64.self, forKey: .sizeBytes),
                  contentHash: try c.decode(String.self, forKey: .contentHash), contentKeyHex: try c.decode(String.self, forKey: .contentKeyHex),
                  chunkCount: try c.decode(Int.self, forKey: .chunkCount), addedAt: try c.decode(Int64.self, forKey: .addedAt),
                  addedBy: try c.decode(String.self, forKey: .addedBy),
                  state: try c.decodeIfPresent(String.self, forKey: .state) ?? VaultSlotPayload.active,
                  revision: try c.decodeIfPresent(Int64.self, forKey: .revision) ?? 0,
                  pad: try c.decodeIfPresent(String.self, forKey: .pad) ?? "")
    }
}

/// One document, as a vault shows it: the newest revision among its copies, and where they sit.
public struct VaultItem: Equatable, Identifiable, Sendable {
    public let payload: VaultSlotPayload
    public let slots: [Int]
    public var id: String { payload.fileId }
    public var isActive: Bool { payload.state == VaultSlotPayload.active }
}

public enum VaultCrypto {
    public static let slotCount = 256
    public static let slotSize = 512
    public static let containerBytes = slotCount * slotSize
    /// 512 − 12-byte nonce − 16-byte tag: every payload is padded to exactly this.
    public static let payloadBytes = slotSize - 12 - 16
    public static let replicas = 2
    public static let maxItems = 64
    public static let minPassphraseLength = 16

    /// Argon2id(code, salt = SHA-256(groupId)[0..15], 32 bytes, INTERACTIVE). Slow on purpose.
    public static func key(code: String, groupId: String) throws -> SymmetricKey {
        try SodiumRaw.ensureInitialized()
        let salt = Array(SHA256.hash(data: Data(groupId.utf8)).prefix(SodiumRaw.pwhashSaltBytes))
        return SymmetricKey(data: try SodiumRaw.argon2idInteractive(password: Array(code.utf8), salt: salt, outLength: 32))
    }

    public static func randomContainer() -> Data {
        var bytes = [UInt8](repeating: 0, count: containerBytes)
        precondition(SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess)
        return Data(bytes)
    }

    /// This key's walk over the slots: Fisher-Yates driven by a SHA-256 stream, so it matches Android.
    public static func slotOrder(_ key: SymmetricKey) -> [Int] {
        var order = Array(0..<slotCount)
        var block = Array(SHA256.hash(data: key.withUnsafeBytes { Data($0) } + Data("slots".utf8)))
        var offset = 0
        for i in stride(from: slotCount - 1, through: 1, by: -1) {
            if offset + 4 > block.count { block = Array(SHA256.hash(data: Data(block))); offset = 0 }
            let v = UInt32(block[offset]) << 24 | UInt32(block[offset + 1]) << 16 | UInt32(block[offset + 2]) << 8 | UInt32(block[offset + 3])
            offset += 4
            order.swapAt(i, Int(v % UInt32(i + 1)))
        }
        return order
    }

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.withoutEscapingSlashes]
        return e
    }()

    /// The payload's JSON padded with dots to exactly `payloadBytes`; nil if it can't fit.
    static func padded(_ payload: VaultSlotPayload) -> Data? {
        var p = payload
        p.pad = ""
        guard let bare = try? encoder.encode(p), bare.count <= payloadBytes else { return nil }
        p.pad = String(repeating: ".", count: payloadBytes - bare.count)
        guard let full = try? encoder.encode(p), full.count == payloadBytes else { return nil }
        return full
    }

    /// Shortens the name until the payload fits a slot.
    public static func fitName(_ payload: VaultSlotPayload) -> VaultSlotPayload {
        var p = payload
        while padded(p) == nil, p.name.count > 1 { p.name = String(p.name.dropLast(max(1, p.name.count / 8))) }
        return p
    }

    public static func seal(_ payload: VaultSlotPayload, key: SymmetricKey) throws -> Data {
        guard let plain = padded(payload), let combined = try AES.GCM.seal(plain, using: key).combined,
              combined.count == slotSize else { throw CocoaError(.coderInvalidValue) }
        return combined
    }

    public static func open(_ slot: Data, key: SymmetricKey) -> VaultSlotPayload? {
        guard slot.count == slotSize, let box = try? AES.GCM.SealedBox(combined: slot),
              let plain = try? AES.GCM.open(box, using: key) else { return nil }
        return try? JSONDecoder().decode(VaultSlotPayload.self, from: plain)
    }

    static func slot(_ container: Data, _ i: Int) -> Data {
        container.subdata(in: (container.startIndex + i * slotSize)..<(container.startIndex + (i + 1) * slotSize))
    }

    /// Every slot is tried, whatever the outcome: no early exit, so no timing difference.
    public static func openAll(_ container: Data, key: SymmetricKey) -> [VaultItem] {
        guard container.count == containerBytes else { return [] }
        var found: [(Int, VaultSlotPayload)] = []
        for i in 0..<slotCount {
            if let p = open(slot(container, i), key: key) { found.append((i, p)) }
        }
        return Dictionary(grouping: found, by: { $0.1.fileId }).map { _, copies in
            VaultItem(payload: copies.max { $0.1.revision < $1.1.revision }!.1, slots: copies.map(\.0).sorted())
        }.sorted { $0.payload.addedAt > $1.payload.addedAt }
    }

    /// Up to two slots for this item: its own first, then this key's walk, taking slots that don't
    /// open under this key (which may belong to a vault this phone can't see: inherent, spec §6.8).
    public static func allocate(_ container: Data, key: SymmetricKey, fileId: String, existing: [Int] = []) -> [Int] {
        var chosen = Array(existing.filter { (0..<slotCount).contains($0) }.prefix(replicas))
        for i in slotOrder(key) where chosen.count < replicas && !chosen.contains(i) {
            if let occupant = open(slot(container, i), key: key), occupant.fileId != fileId { continue }
            chosen.append(i)
        }
        return chosen
    }

    /// Seals the payload into its slots, each with its own nonce. Nil when there's no room.
    public static func write(_ payload: VaultSlotPayload, into container: Data, key: SymmetricKey, existing: [Int] = []) -> Data? {
        guard container.count == containerBytes else { return nil }
        let slots = allocate(container, key: key, fileId: payload.fileId, existing: existing)
        guard !slots.isEmpty else { return nil }
        var next = container
        for i in slots {
            guard let sealed = try? seal(payload, key: key) else { return nil }
            next.replaceSubrange((next.startIndex + i * slotSize)..<(next.startIndex + (i + 1) * slotSize), with: sealed)
        }
        return next
    }
}

// Wire messages (spec §6.8).

/// Retained on `familysafe/group/{gid}/vault/container`. Signed over `{version}|{data}`.
public struct VaultContainerMessage: Codable, Equatable, Sendable {
    public let version: Int64
    public let data: String
    public let signerMemberId: String
    public let signature: String

    public init(version: Int64, data: String, signerMemberId: String, signature: String) {
        self.version = version
        self.data = data
        self.signerMemberId = signerMemberId
        self.signature = signature
    }

    private enum CodingKeys: String, CodingKey { case version, data, signerMemberId, signature }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(version: try c.decode(Int64.self, forKey: .version), data: try c.decode(String.self, forKey: .data),
                  signerMemberId: try c.decodeIfPresent(String.self, forKey: .signerMemberId) ?? "",
                  signature: try c.decodeIfPresent(String.self, forKey: .signature) ?? "")
    }

    public static func signingPayload(version: Int64, data: String) -> String { "\(version)|\(data)" }
}

public struct VaultChunkMessage: Codable, Equatable, Sendable {
    public let fileId: String
    public let chunkIndex: Int
    public let totalChunks: Int
    public let data: String
}

/// Plaintext, on a peer's `vault/repair`, as Android sends it.
public struct VaultChunkRequest: Codable, Equatable, Sendable {
    public let requesterId: String
    public let fileId: String
    public let missing: [Int]
    public let timestamp: Int64

    public init(requesterId: String, fileId: String, missing: [Int], timestamp: Int64) {
        self.requesterId = requesterId
        self.fileId = fileId
        self.missing = missing
        self.timestamp = timestamp
    }

    private enum CodingKeys: String, CodingKey { case requesterId, fileId, missing, timestamp }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(requesterId: try c.decode(String.self, forKey: .requesterId), fileId: try c.decode(String.self, forKey: .fileId),
                  missing: try c.decode([Int].self, forKey: .missing), timestamp: try c.decodeIfPresent(Int64.self, forKey: .timestamp) ?? 0)
    }
}

/// A vault's key held in memory only, and handed between the UI and the node.
public struct SymmetricKeyBox: @unchecked Sendable {
    public let key: SymmetricKey
    public init(_ key: SymmetricKey) { self.key = key }
}
