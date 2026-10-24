import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// B5: shared files, receive side (spec §6.7), matching Android SharedFileRepository.
final class SharedFileTests: XCTestCase {
    private let groupKey = String(repeating: "ab", count: 32)

    private func id(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }
    private func member(_ i: FamilySafeIdentity) -> FamilyMember {
        FamilyMember(memberId: i.memberId, displayName: "x", ed25519PublicKey: i.ed25519PublicKeyHex,
                     x25519PublicKey: i.x25519PublicKeyHex, addedAtEpochMs: 1)
    }
    private func family(_ people: [FamilySafeIdentity], key: String? = nil) -> GroupDefinition {
        GroupDefinition(groupId: "g-1", groupName: "F", createdAtEpochMs: 1, creatorMemberId: people[0].memberId,
                        members: people.map(member), version: 1, fileEncryptionKey: key)
    }
    private func file(_ id: String = "f-1", chunks: Int = 3, essential: Bool = true, hash: String = "h", kid: String? = nil) -> SharedFile {
        SharedFile(fileId: id, name: "card.pdf", mimeType: "application/pdf", sizeBytes: 70_000, contentHash: hash,
                   uploaderMemberId: "u", uploadedAt: 5, chunkCount: chunks, isEssential: essential, kid: kid)
    }

    // MARK: Wire

    func test_androidOmitsDefaults() throws {
        let f = try JSONDecoder().decode(SharedFile.self, from: Data(
            #"{"fileId":"f","name":"n","mimeType":"m","sizeBytes":1,"contentHash":"h","uploaderMemberId":"u","uploadedAt":1,"chunkCount":1}"#.utf8))
        XCTAssertTrue(f.isEssential, "absent isEssential means true")
        XCTAssertFalse(f.isDeleted)
        let c = try JSONDecoder().decode(FileChunkMessage.self, from: Data(#"{"fileId":"f","chunkIndex":0,"totalChunks":1,"data":""}"#.utf8))
        XCTAssertEqual(c.keyVersion, 1, "absent keyVersion means 1")
        let m = try JSONDecoder().decode(EncryptedFileManifest.self, from: Data(#"{"keyVersion":2,"data":"x"}"#.utf8))
        XCTAssertEqual(m.signature, "", "an older sender's unsigned manifest decodes, to be refused")
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(EncryptedFileMessage(keyVersion: 3, data: "d")), as: UTF8.self).contains("kid"),
                       "no kid on the wire for the epoch-0 key")
    }

    // MARK: Keys

    func test_keyVersions() throws {
        let keyed = family([try id("a")], key: groupKey)
        XCTAssertNil(FileKeys.key(keyed, keyVersion: 1, kid: nil), "the public legacy key is refused once a family has a key")
        XCTAssertEqual(FileKeys.key(keyed, keyVersion: 2, kid: nil)?.withUnsafeBytes { Data($0) }, Hex.decode(groupKey).map { Data($0) })
        let v3 = Data(SHA256.hash(data: Data(Hex.decode(groupKey)!) + Data("files".utf8)))
        XCTAssertEqual(FileKeys.key(keyed, keyVersion: 3, kid: nil)?.withUnsafeBytes { Data($0) }, v3)
        XCTAssertNil(FileKeys.key(keyed, keyVersion: 4, kid: nil))
        XCTAssertNil(FileKeys.key(keyed, keyVersion: 3, kid: "0123456789abcdef"), "a kid we don't hold opens nothing")
        let legacy = family([try id("a")])
        XCTAssertEqual(FileKeys.key(legacy, keyVersion: 1, kid: nil)?.withUnsafeBytes { Data($0) },
                       Data(SHA256.hash(data: Data("g-1familysafety-files-v1".utf8))))
        XCTAssertNil(FileKeys.key(legacy, keyVersion: 2, kid: nil))
        XCTAssertEqual(FileKeys.writeKey(keyed).version, 3, "write 3")
    }

    // MARK: Manifest

    func test_manifest_roundTripsPaddedSignedAndEncrypted() throws {
        let ana = try id("ana")
        let g = family([ana], key: groupKey)
        let manifest = FileManifest(groupId: "g-1", files: [file()], version: 9)
        let raw = try FileCodec.sealManifest(manifest, group: g, identity: ana)
        let wrapped = try JSONDecoder().decode(EncryptedFileManifest.self, from: raw)
        XCTAssertEqual((Data(base64Encoded: wrapped.data)!.count - 28) % FileCodec.manifestPadBytes, 0, "plaintext on the 2 KiB grid")
        XCTAssertFalse(String(decoding: raw, as: UTF8.self).contains("card.pdf"))
        XCTAssertEqual(FileCodec.openManifest(raw, group: g)?.files.map(\.fileId), ["f-1"])
    }

    func test_manifest_refusals() throws {
        let ana = try id("ana"), eve = try id("eve")
        let g = family([ana], key: groupKey)
        let raw = try FileCodec.sealManifest(FileManifest(groupId: "g-1", files: [file()], version: 9), group: g, identity: eve)
        XCTAssertNil(FileCodec.openManifest(raw, group: g), "signer not in our roster")
        let good = try JSONDecoder().decode(EncryptedFileManifest.self, from: try FileCodec.sealManifest(
            FileManifest(groupId: "g-1", files: [file()], version: 9), group: g, identity: ana))
        let unsigned = EncryptedFileManifest(keyVersion: good.keyVersion, data: good.data, signerMemberId: "", signature: "")
        XCTAssertNil(FileCodec.openManifest(try JSONEncoder().encode(unsigned), group: g), "absent is not valid")
        let relabelled = EncryptedFileManifest(keyVersion: 2, data: good.data, signerMemberId: good.signerMemberId, signature: good.signature)
        XCTAssertNil(FileCodec.openManifest(try JSONEncoder().encode(relabelled), group: g), "the key version is signed")
        let plaintext = try JSONEncoder().encode(FileManifest(groupId: "g-1", files: [file()], version: 9))
        XCTAssertNil(FileCodec.openManifest(plaintext, group: g), "plaintext refused once the family has a key")
        XCTAssertNotNil(FileCodec.openManifest(plaintext, group: family([ana])), "still read in a family without one")
    }

    // MARK: Chunks

    func test_chunk_acceptedOnlyUnderItsFilesKeyAndTopic() throws {
        let g = family([try id("a")], key: groupKey)
        let (key, version, _) = FileKeys.writeKey(g)
        let ct = try FileCipher.seal(Data("piece".utf8), key: key)
        let raw = try FileCodec.chunkMessage(fileId: "f-1", index: 1, total: 3, ciphertext: ct, keyVersion: version, kid: nil)
        XCTAssertEqual(FileCodec.checkChunk(raw, topicFileId: "f-1", topicIndex: 1, file: file(), group: g), .accept(ciphertext: ct, keyVersion: 3))
        XCTAssertNotEqual(FileCodec.checkChunk(raw, topicFileId: "f-1", topicIndex: 2, file: file(), group: g), .accept(ciphertext: ct, keyVersion: 3))
        XCTAssertEqual(FileCodec.checkChunk(raw, topicFileId: "f-1", topicIndex: 1, file: file(kid: "0123456789abcdef"), group: g),
                       .refuse("different group key"))
        let legacy = try FileCodec.chunkMessage(fileId: "f-1", index: 1, total: 3,
                                                ciphertext: try FileCipher.seal(Data("x".utf8), key: SymmetricKey(data: SharedFileCrypto.deriveFileKey(groupId: "g-1"))),
                                                keyVersion: 1, kid: nil)
        XCTAssertEqual(FileCodec.checkChunk(legacy, topicFileId: "f-1", topicIndex: 1, file: file(), group: g), .refuse("key not held"),
                       "anyone on the broker could plant a version-1 chunk")
        let forged = try FileCodec.chunkMessage(fileId: "f-1", index: 1, total: 3, ciphertext: Data(repeating: 7, count: 60), keyVersion: 3, kid: nil)
        XCTAssertEqual(FileCodec.checkChunk(forged, topicFileId: "f-1", topicIndex: 1, file: file(), group: g), .refuse("failed authentication"))
    }

    // MARK: Library

    func test_manifest_essentialFetchesAtOnce_othersWaitToBeOpened() {
        var lib = FileLibrary()
        let out = lib.apply(FileManifest(groupId: "g-1", files: [file("a"), file("b", essential: false)], version: 1), groupId: "g-1", now: 10)
        XCTAssertEqual(out.fetch, ["a"])
        XCTAssertEqual(lib.due(now: 10), ["a"])
        lib.want("b", now: 11)
        XCTAssertEqual(Set(lib.due(now: 11)), ["a", "b"])
        XCTAssertTrue(lib.apply(FileManifest(groupId: "other", files: [file("c")], version: 1), groupId: "g-1", now: 1).fetch.isEmpty)
        XCTAssertNil(lib.entries["../x"])
        _ = lib.apply(FileManifest(groupId: "g-1", files: [file("../x")], version: 1), groupId: "g-1", now: 1)
        XCTAssertNil(lib.entries["../x"], "an id that isn't a plain id is never recorded")
    }

    func test_deletion_erasesAndTombstonesEvenUnknownFiles() {
        var lib = FileLibrary()
        _ = lib.apply(FileManifest(groupId: "g-1", files: [file("a")], version: 1), groupId: "g-1", now: 1)
        _ = lib.recordChunk(fileId: "a", index: 0, keyVersion: 3, now: 2)
        var gone = file("a"); gone.isDeleted = true; gone.deletedAt = 3
        var never = file("z"); never.isDeleted = true; never.deletedAt = 3
        let out = lib.apply(FileManifest(groupId: "g-1", files: [gone, never], version: 2), groupId: "g-1", now: 4)
        XCTAssertEqual(out.erase, ["a"])
        XCTAssertTrue(lib.entries["a"]!.held.isEmpty)
        XCTAssertTrue(lib.entries["z"]!.file.isDeleted, "the tombstone is kept")
        XCTAssertFalse(lib.accepts(fileId: "z"), "no chunks for a deleted file")
        XCTAssertTrue(lib.apply(FileManifest(groupId: "g-1", files: [file("z")], version: 3), groupId: "g-1", now: 5).fetch.isEmpty,
                      "a slower peer's live copy doesn't bring it back")
        XCTAssertTrue(lib.visible.isEmpty)
    }

    func test_chunks_missingAndCompletion() {
        var lib = FileLibrary()
        _ = lib.apply(FileManifest(groupId: "g-1", files: [file("a", chunks: 3)], version: 1), groupId: "g-1", now: 1)
        XCTAssertEqual(lib.recordChunk(fileId: "a", index: 1, keyVersion: 3, now: 2), .stored(complete: false))
        XCTAssertEqual(lib.recordChunk(fileId: "a", index: 1, keyVersion: 3, now: 2), .ignored("duplicate"))
        XCTAssertEqual(lib.recordChunk(fileId: "a", index: 0, keyVersion: 2, now: 2), .ignored("other key version"))
        XCTAssertEqual(lib.missing("a"), [0, 2])
        _ = lib.recordChunk(fileId: "a", index: 0, keyVersion: 3, now: 3)
        XCTAssertEqual(lib.recordChunk(fileId: "a", index: 2, keyVersion: 3, now: 3), .stored(complete: true))
        lib.markComplete("a")
        XCTAssertFalse(lib.accepts(fileId: "a"))
        XCTAssertNil(lib.nextDueAtMs())
    }

    func test_repair_prefersCompleteHoldersWithTheSameBytes_rotates_backsOff() {
        var lib = FileLibrary()
        _ = lib.apply(FileManifest(groupId: "g-1", files: [file("a", hash: "H")], version: 1), groupId: "g-1", now: 0)
        lib.record(FileHoldingsAnnouncement(announcerId: "ben", holdings: [FileHolding(fileId: "a", contentHash: "OTHER", state: "COMPLETE", chunksHeld: 3, chunkCount: 3)], timestamp: 0),
                   from: "ben", members: ["ben", "dana"])
        lib.record(FileHoldingsAnnouncement(announcerId: "dana", holdings: [FileHolding(fileId: "a", contentHash: "H", state: "COMPLETE", chunksHeld: 3, chunkCount: 3)], timestamp: 0),
                   from: "dana", members: ["ben", "dana"])
        let first = lib.repairRequest("a", me: "me", peers: ["ben", "dana"], now: 0)
        XCTAssertEqual(first?.peerId, "dana", "Ben's copy is different bytes, so it isn't a copy")
        XCTAssertEqual(first?.request.missing, [0, 1, 2])
        XCTAssertEqual(lib.entries["a"]?.nextAttemptAtMs, 30_000)
        _ = lib.repairRequest("a", me: "me", peers: ["ben", "dana"], now: 30_000)
        XCTAssertEqual(lib.entries["a"]?.nextAttemptAtMs, 90_000, "doubling backoff")
        XCTAssertEqual(lib.copiesElsewhere("a"), 1)
        XCTAssertEqual(FileLibrary.backoff(attempt: 20), FileLibrary.repairMaxBackoffMs)
        var fresh = FileLibrary()
        _ = fresh.apply(FileManifest(groupId: "g-1", files: [file("a")], version: 1), groupId: "g-1", now: 0)
        XCTAssertNil(fresh.copiesElsewhere("a"), "nobody has announced: unknown, not zero")
        XCTAssertEqual(fresh.repairRequest("a", me: "me", peers: ["ben", "dana"], now: 0)?.peerId, "ben")
        XCTAssertEqual(fresh.repairRequest("a", me: "me", peers: ["ben", "dana"], now: 1)?.peerId, "dana", "rotates")
    }

    func test_serve_onlyAWholeCopyOfTheSameBytes_capped() {
        var lib = FileLibrary()
        _ = lib.apply(FileManifest(groupId: "g-1", files: [file("a", chunks: 100, hash: "H")], version: 1), groupId: "g-1", now: 0)
        let ask = ChunkRepairRequest(requestId: "r", requesterId: "ben", fileId: "a", contentHash: "H", totalChunks: 100,
                                     missing: Array(0..<100) + [500, -1], timestamp: 0)
        XCTAssertNil(lib.serve(ask, from: "ben"), "not complete here")
        for i in 0..<100 { _ = lib.recordChunk(fileId: "a", index: i, keyVersion: 3, now: 1) }
        lib.markComplete("a")
        XCTAssertEqual(lib.serve(ask, from: "ben")?.indices, Array(0..<64), "at most 64, in range")
        let other = ChunkRepairRequest(requestId: "r", requesterId: "ben", fileId: "a", contentHash: "X", totalChunks: 100, missing: [1], timestamp: 0)
        XCTAssertNil(lib.serve(other, from: "ben"), "different bytes: refuse rather than corrupt theirs")
        XCTAssertNil(lib.serve(ask, from: "sam"), "requesterId must be who asked")
    }

    func test_announcement_isDebounced_andOnlyMembersCount() {
        var lib = FileLibrary()
        _ = lib.apply(FileManifest(groupId: "g-1", files: [file("a")], version: 1), groupId: "g-1", now: 0)
        XCTAssertNil(lib.announcement(me: "me", now: 0), "nothing held, nothing to say")
        _ = lib.recordChunk(fileId: "a", index: 0, keyVersion: 3, now: 0)
        XCTAssertEqual(lib.announcement(me: "me", now: 0)?.holdings.first?.state, "PARTIAL")
        XCTAssertNil(lib.announcement(me: "me", now: 29_999))
        XCTAssertNotNil(lib.announcement(me: "me", now: 29_999, force: true))
        lib.record(FileHoldingsAnnouncement(announcerId: "eve", holdings: [], timestamp: 0), from: "eve", members: ["ben"])
        XCTAssertTrue(lib.holdings.isEmpty)
    }

    // MARK: Storage

    func test_store_refusesUnsafeIds_andVerifiesTheWhole() throws {
        XCTAssertFalse(ChunkStore.isSafeId("../etc"))
        XCTAssertFalse(ChunkStore.isSafeId(""))
        XCTAssertTrue(ChunkStore.isSafeId("6f1e2d3c-aaaa-bbbb-cccc-0123456789ab"))
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let store = ChunkStore(root: tmp.appendingPathComponent("files"), viewRoot: tmp.appendingPathComponent("views"))
        XCTAssertThrowsError(try store.write(fileId: "../x", index: 0, ciphertext: Data()))
        let key = SymmetricKey(size: .bits256)
        let parts = [Data("hello ".utf8), Data("world".utf8)]
        for (i, p) in parts.enumerated() { try store.write(fileId: "f", index: i, ciphertext: try FileCipher.seal(p, key: key)) }
        let expected = SHA256.hash(data: Data("hello world".utf8)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(store.verifiedHash(fileId: "f", chunkCount: 2, key: key), expected)
        XCTAssertNil(store.verifiedHash(fileId: "f", chunkCount: 3, key: key), "a missing chunk")
        let url = try store.materialize(fileId: "f", name: "note.txt", chunkCount: 2, key: key)
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), "hello world")
        store.clearViews()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "view copies are cleared")
    }
}
