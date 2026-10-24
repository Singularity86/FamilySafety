import CryptoKit
import Foundation

/// Which key a file chunk, manifest or file message is under (spec §6.7 "File key versions", §6.5
/// keyring). Ports Android FileKeys.kt and SharedFileRepository.keyForVersion.
public enum FileKeys {
    /// SHA-256(groupId + "familysafety-files-v1"): public inputs, read-only compatibility.
    public static let legacyVersion = 1
    /// The group key itself.
    public static let groupSecretVersion = 2
    /// SHA-256(groupKey ‖ "files"): what new uploads use.
    public static let subkeyVersion = 3

    /// The key for `keyVersion` (and `kid`) in this family, or nil when it must be refused: version 1
    /// in a family with a group key, a kid this phone doesn't hold, or an unknown version.
    public static func key(_ group: GroupDefinition, keyVersion: Int, kid: String?) -> SymmetricKey? {
        let groupKeyHex: String?
        if let kid {
            guard let named = GroupKeys.keyHex(group, kid: kid) else { return nil }   // never the legacy key
            groupKeyHex = named
        } else {
            groupKeyHex = group.fileEncryptionKey.flatMap { $0.isEmpty ? nil : $0 }
        }
        guard let groupKeyHex else {
            return keyVersion == legacyVersion || keyVersion == 0
                ? SymmetricKey(data: SharedFileCrypto.deriveFileKey(groupId: group.groupId)) : nil
        }
        guard let bytes = Hex.decode(groupKeyHex), bytes.count == 32 else { return nil }
        switch keyVersion {
        case subkeyVersion: return SymmetricKey(data: Data(SHA256.hash(data: Data(bytes) + Data("files".utf8))))
        case groupSecretVersion, 0: return SymmetricKey(data: Data(bytes))
        default: return nil
        }
    }

    /// What to write with: version 3 under the current key, stamped with its kid (nil for epoch 0);
    /// a family with no group key falls back to the public legacy key (a known gap).
    public static func writeKey(_ group: GroupDefinition) -> (key: SymmetricKey, version: Int, kid: String?) {
        let kid = GroupKeys.stamp(group)
        if let k = key(group, keyVersion: subkeyVersion, kid: kid) { return (k, subkeyVersion, kid) }
        return (SymmetricKey(data: SharedFileCrypto.deriveFileKey(groupId: group.groupId)), legacyVersion, nil)
    }
}

/// AES-256-GCM with a random 12-byte nonce and 128-bit tag, blob `nonce ‖ ciphertext ‖ tag`
/// (CryptoKit's `combined`, identical to Android's layout).
public enum FileCipher {
    public static func seal(_ plaintext: Data, key: SymmetricKey) throws -> Data {
        guard let combined = try AES.GCM.seal(plaintext, using: key).combined else { throw CocoaError(.coderInvalidValue) }
        return combined
    }

    public static func open(_ blob: Data, key: SymmetricKey) throws -> Data {
        try AES.GCM.open(AES.GCM.SealedBox(combined: blob), using: key)
    }
}

public enum FileCodec {
    /// The manifest pad grid: 2 KiB, so ciphertext length doesn't reveal file count or name lengths.
    public static let manifestPadBytes = 2048

    /// Decodes a manifest, authenticating before decrypting (§6.7 "Manifest authenticity"): the
    /// signer must be in *our* roster and the signature over `{keyVersion}|[{kid}|]{data}` must
    /// verify. A plaintext manifest is taken only in a family without a group key.
    public static func openManifest(_ raw: Data, group: GroupDefinition) -> FileManifest? {
        if let wrapped = try? JSONDecoder().decode(EncryptedFileManifest.self, from: raw) {
            guard !wrapped.signature.isEmpty, !wrapped.signerMemberId.isEmpty,
                  let signer = group.findMember(byId: wrapped.signerMemberId),
                  let key = Hex.decode(signer.ed25519PublicKey), let sig = Hex.decode(wrapped.signature),
                  (try? SodiumRaw.ensureInitialized()) != nil,
                  SodiumRaw.signVerifyDetached(signature: sig, message: Array(EncryptedFileManifest.signingPayload(
                      keyVersion: wrapped.keyVersion, data: wrapped.data, kid: wrapped.kid).utf8), publicKey: key),
                  let fileKey = FileKeys.key(group, keyVersion: wrapped.keyVersion, kid: wrapped.kid),
                  let blob = Data(base64Encoded: wrapped.data),
                  let plain = try? FileCipher.open(blob, key: fileKey) else { return nil }
            return try? JSONDecoder().decode(FileManifest.self, from: plain)
        }
        guard group.fileEncryptionKey?.isEmpty ?? true else { return nil }   // stale, or an attempt
        return try? JSONDecoder().decode(FileManifest.self, from: raw)
    }

    /// Pads, encrypts with the current file key and signs, ready to publish retained.
    public static func sealManifest(_ manifest: FileManifest, group: GroupDefinition, identity: FamilySafeIdentity) throws -> Data {
        let (key, version, kid) = FileKeys.writeKey(group)
        guard version != FileKeys.legacyVersion else {
            // No group key: the only key is public, so encrypting would be theatre (spec §6.7).
            return try JSONEncoder().encode(manifest)
        }
        let data = try FileCipher.seal(try JSONEncoder().encode(padded(manifest)), key: key).base64EncodedString()
        let signature = try AuthorSignatures.sign(EncryptedFileManifest.signingPayload(keyVersion: version, data: data, kid: kid),
                                                  identity: identity)
        return try JSONEncoder().encode(EncryptedFileManifest(keyVersion: version, data: data, signerMemberId: identity.memberId,
                                                              signature: signature, kid: kid))
    }

    static func padded(_ manifest: FileManifest) throws -> FileManifest {
        var bare = manifest
        bare.pad = ""
        let size = try JSONEncoder().encode(bare).count
        let target = (size + manifestPadBytes - 1) / manifestPadBytes * manifestPadBytes
        bare.pad = String(repeating: ".", count: target - size)
        return bare
    }

    /// A file control message's inner JSON, or nil (wrong key, refused version, forged).
    public static func openMessage(_ raw: Data, group: GroupDefinition) -> Data? {
        guard let wrapped = try? JSONDecoder().decode(EncryptedFileMessage.self, from: raw),
              let key = FileKeys.key(group, keyVersion: wrapped.keyVersion, kid: wrapped.kid),
              let blob = Data(base64Encoded: wrapped.data) else { return nil }
        return try? FileCipher.open(blob, key: key)
    }

    public static func sealMessage<T: Encodable>(_ value: T, group: GroupDefinition) throws -> Data {
        let (key, version, kid) = FileKeys.writeKey(group)
        let data = try FileCipher.seal(try JSONEncoder().encode(value), key: key).base64EncodedString()
        return try JSONEncoder().encode(EncryptedFileMessage(keyVersion: version, data: data, kid: kid))
    }

    public enum ChunkVerdict: Equatable {
        /// Authenticated: store this ciphertext, verbatim, under this key version.
        case accept(ciphertext: Data, keyVersion: Int)
        case refuse(String)
    }

    /// Checks a chunk against its topic and its file's entry, then authenticates it under the key
    /// it declares. The plaintext is not kept.
    public static func checkChunk(_ raw: Data, topicFileId: String, topicIndex: Int, file: SharedFile,
                                  group: GroupDefinition) -> ChunkVerdict {
        guard let m = try? JSONDecoder().decode(FileChunkMessage.self, from: raw) else { return .refuse("unreadable") }
        guard m.fileId == topicFileId, m.chunkIndex == topicIndex, m.fileId == file.fileId else { return .refuse("topic mismatch") }
        guard m.chunkIndex >= 0, m.chunkIndex < file.chunkCount, m.totalChunks == file.chunkCount else { return .refuse("out of range") }
        // A chunk must be under its file's key, so a removed member can't plant one (3.3).
        guard (m.kid?.lowercased() ?? "") == (file.kid?.lowercased() ?? "") else { return .refuse("different group key") }
        guard let key = FileKeys.key(group, keyVersion: m.keyVersion, kid: m.kid) else { return .refuse("key not held") }
        guard let blob = Data(base64Encoded: m.data), (try? FileCipher.open(blob, key: key)) != nil else {
            return .refuse("failed authentication")
        }
        return .accept(ciphertext: blob, keyVersion: m.keyVersion)
    }

    public static func chunkMessage(fileId: String, index: Int, total: Int, ciphertext: Data, keyVersion: Int, kid: String?) throws -> Data {
        try JSONEncoder().encode(FileChunkMessage(fileId: fileId, chunkIndex: index, totalChunks: total,
                                                  data: ciphertext.base64EncodedString(), keyVersion: keyVersion, kid: kid))
    }
}
