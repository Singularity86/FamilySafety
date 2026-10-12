import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// Phase 3a: memberships and removal proofs (Android 1.15.3 parity), merge, the state store
/// (including the review's F14/F26 rules) and the sync engine end to end.
final class GroupSyncTests: XCTestCase {

    // MARK: - Fixtures

    private func identity(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }

    private func member(_ id: FamilySafeIdentity, _ name: String, addedAt: Int64 = 1_700_000_000_000, epoch: Int = 0) -> FamilyMember {
        FamilyMember(memberId: id.memberId, displayName: name, ed25519PublicKey: id.ed25519PublicKeyHex,
                     x25519PublicKey: id.x25519PublicKeyHex, addedAtEpochMs: addedAt, joinEpoch: epoch)
    }

    private func group(_ members: [FamilyMember], creator: FamilySafeIdentity, version: Int64,
                       previous: String? = nil, removed: [String] = [], proofs: [RemovalProof] = [],
                       name: String = "Fam") -> GroupDefinition {
        GroupDefinition(groupId: "g-1", groupName: name, createdAtEpochMs: 1_700_000_000_000,
                        creatorMemberId: creator.memberId, members: members, version: version,
                        previousStateHash: previous, fileEncryptionKey: String(repeating: "ab", count: 32),
                        removedMemberIds: removed, removalProofs: proofs)
    }

    private func proof(_ tombstone: String, by signer: FamilySafeIdentity) throws -> RemovalProof {
        let message = Array(RemovalProof.signingPayload(groupId: "g-1", tombstone: tombstone).utf8)
        let sig = try SodiumRaw.signDetached(message: message, secretKey: signer.ed25519SecretKey64)
        return RemovalProof(tombstone: tombstone, authorizerMemberId: signer.memberId, signature: Hex.encode(sig))
    }

    /// Captures what the engine would publish.
    actor CapturingOutbox: GroupSyncOutbox {
        struct Sent { let topic: String; let payload: Data }
        private(set) var sent: [Sent] = []
        func send(topic: String, payload: Data, qos: Int, retained: Bool) async { sent.append(Sent(topic: topic, payload: payload)) }
        func clear() { sent = [] }
        func to(_ topic: String) -> [Data] { sent.filter { $0.topic == topic }.map(\.payload) }
    }

    // MARK: - Small fixes (plan Part A)

    func test_hex_roundTripsAndRejectsNonHex() {
        let bytes: [UInt8] = [0x00, 0x0f, 0xa5, 0xff]
        XCTAssertEqual(Hex.encode(bytes), "000fa5ff")
        XCTAssertEqual(Hex.decode("000FA5ff"), bytes)
        XCTAssertNil(Hex.decode("abc"))
        XCTAssertNil(Hex.decode("zz"))
        XCTAssertNil(Hex.decode("éé"))
    }

    func test_identity_throwsOnBadSeedInsteadOfCrashing() {
        XCTAssertThrowsError(try FamilySafeKeyDerivation.deriveIdentity(seed: [1, 2, 3])) {
            XCTAssertEqual($0 as? IdentityError, .invalidSeedLength(3))
        }
    }

    // MARK: - Memberships (wire parity with Android 1.15.3)

    func test_stateHash_includesJoinEpochOnlyWhenPositive_matchesAndroidCanonicalForm() {
        let creator = String(repeating: "aa", count: 16)
        let g = GroupDefinition(
            groupId: "g-1", groupName: "Fam", createdAtEpochMs: 1_700_000_000_000, creatorMemberId: creator,
            members: [
                FamilyMember(memberId: String(repeating: "bb", count: 16), displayName: "B", ed25519PublicKey: String(repeating: "11", count: 32),
                             x25519PublicKey: String(repeating: "22", count: 32), addedAtEpochMs: 0, joinEpoch: 2),
                FamilyMember(memberId: creator, displayName: "A", ed25519PublicKey: String(repeating: "33", count: 32),
                             x25519PublicKey: String(repeating: "44", count: 32), addedAtEpochMs: 0),
            ],
            version: 7, removedMemberIds: [String(repeating: "cc", count: 16), String(repeating: "bb", count: 16) + "#1"]
        )
        // Computed independently in Python from Models.kt's canonical string.
        XCTAssertEqual(g.computeStateHash(), "1d3861c69cb7b77277b166cede83d809f22253090220f63b4977687f264dccc7")
    }

    func test_tombstones_keysAndNextEpoch() {
        XCTAssertEqual(Tombstones.key(memberId: "m", joinEpoch: 0), "m")
        XCTAssertEqual(Tombstones.key(memberId: "m", joinEpoch: 3), "m#3")
        XCTAssertEqual(Tombstones.memberId(of: "m#3"), "m")
        XCTAssertEqual(Tombstones.epoch(of: "m#3"), 3)
        XCTAssertEqual(Tombstones.epoch(of: "m"), 0)
        XCTAssertEqual(Tombstones.nextJoinEpoch(memberId: "m", tombstones: ["m", "m#1", "x#5"]), 2)
        XCTAssertEqual(Tombstones.nextJoinEpoch(memberId: "n", tombstones: ["m"]), 0)
    }

    func test_decodesAndroidShapedJsonWithOmittedDefaultsAndCreatorTransferred() throws {
        let json = """
        {"groupId":"g-1","version":2,"updaterMemberId":"u","changeType":"CREATOR_TRANSFERRED","timestamp":1,"signature":"00",
         "groupDefinition":{"groupId":"g-1","groupName":"Fam","createdAtEpochMs":1,"creatorMemberId":"u","version":2,
           "members":[{"memberId":"u","displayName":"U","ed25519PublicKey":"aa","x25519PublicKey":"bb","addedAtEpochMs":1}]}}
        """
        let message = try JSONDecoder().decode(GroupSyncMessage.self, from: Data(json.utf8))
        XCTAssertEqual(message.changeType, .creatorTransferred)
        XCTAssertEqual(message.groupDefinition.members.first?.joinEpoch, 0)
        XCTAssertEqual(message.groupDefinition.removalProofs, [])
        XCTAssertEqual(message.groupDefinition.removedMemberIds, [])
    }

    func test_validator_acceptsReadmissionOnlyAfterOldMembershipEnded() throws {
        let alice = try identity("alice"), bob = try identity("bob")
        let current = group([member(alice, "A"), member(bob, "B")], creator: alice, version: 3)
        // Bob back as membership 1 without ending membership 0: refused.
        let sneaky = group([member(alice, "A"), member(bob, "B", epoch: 1)], creator: alice, version: 4, previous: current.computeStateHash())
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: sneaky, updaterMemberId: alice.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .membershipChangedWithoutRemoval)
        }
        // Ending membership 0 (tombstone "bob") and starting membership 1: accepted.
        let proper = group([member(alice, "A"), member(bob, "B", epoch: 1)], creator: alice, version: 4,
                           previous: current.computeStateHash(), removed: [bob.memberId])
        XCTAssertNoThrow(try GroupTransitionValidator.validate(current: current, remote: proper, updaterMemberId: alice.memberId))
    }

    func test_validator_relayedRemovalNeedsCreatorOrSelfProof() throws {
        let alice = try identity("alice"), bob = try identity("bob"), carol = try identity("carol")
        let current = group([member(alice, "A"), member(bob, "B"), member(carol, "C")], creator: alice, version: 1)
        // Carol relays Alice's (the creator's) removal of Bob, carrying Alice's proof.
        let relayed = group([member(alice, "A"), member(carol, "C")], creator: alice, version: 2,
                            previous: current.computeStateHash(), removed: [bob.memberId],
                            proofs: [try proof(bob.memberId, by: alice)])
        let authorizers = GroupTransitionValidator.verifiedRemovalAuthorizers(current: current, remote: relayed)
        XCTAssertEqual(authorizers, [bob.memberId: alice.memberId])
        XCTAssertNoThrow(try GroupTransitionValidator.validate(current: current, remote: relayed, updaterMemberId: carol.memberId,
                                                               verifiedRemovalAuthorizers: authorizers))
        // The same relay with a "proof" signed by Carol herself authorizes nothing.
        let forged = relayed.with(removalProofs: [try proof(bob.memberId, by: carol)])
        let forgedAuth = GroupTransitionValidator.verifiedRemovalAuthorizers(current: current, remote: forged)
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: forged, updaterMemberId: carol.memberId,
                                                                   verifiedRemovalAuthorizers: forgedAuth)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .unauthorizedNewTombstone)
        }
    }

    // MARK: - Merge

    func test_merge_unionsMembersAndTombstones_andIsNilWhenNothingToAdd() throws {
        let alice = try identity("alice"), bob = try identity("bob"), carol = try identity("carol")
        let base = group([member(alice, "A")], creator: alice, version: 2)
        let withBob = group([member(alice, "A"), member(bob, "B")], creator: alice, version: 3)
        let withCarol = group([member(alice, "A"), member(carol, "C")], creator: alice, version: 3)
        let winner = GroupStateMerge.pickWinner(withBob, withCarol)
        let loser = winner == withBob ? withCarol : withBob
        let merged = try XCTUnwrap(GroupStateMerge.merge(winner: winner, ours: loser))
        XCTAssertEqual(Set(merged.members.map(\.memberId)), [alice.memberId, bob.memberId, carol.memberId])
        XCTAssertEqual(merged.version, 4)
        XCTAssertEqual(merged.previousStateHash, winner.computeStateHash())
        XCTAssertNil(GroupStateMerge.merge(winner: withBob, ours: base))
    }

    func test_merge_recomputesSuccessorWhenWinnersCreatorWasRemovedElsewhere() throws {
        let alice = try identity("alice"), bob = try identity("bob"), carol = try identity("carol")
        let roster = [member(alice, "A", addedAt: 1), member(bob, "B", addedAt: 2), member(carol, "C", addedAt: 3)]
        let winner = group(roster, creator: alice, version: 5)
        let ours = group(Array(roster.dropFirst()), creator: alice, version: 5, removed: [alice.memberId])
        let merged = try XCTUnwrap(GroupStateMerge.merge(winner: winner, ours: ours))
        XCTAssertFalse(merged.containsMember(alice.memberId))
        XCTAssertEqual(merged.creatorMemberId, bob.memberId)
    }

    // MARK: - Store

    func test_store_unionsOurTombstonesIntoAStateThatLacksThem() async throws {
        let alice = try identity("alice"), bob = try identity("bob"), dave = try identity("dave")
        let current = group([member(alice, "A"), member(bob, "B")], creator: alice, version: 2, removed: [dave.memberId])
        let store = GroupStateStore(localMemberId: bob.memberId, persistence: InMemoryGroupStatePersistence(initial: current))
        try await store.load()
        let remote = group([member(alice, "A"), member(bob, "B")], creator: alice, version: 3, previous: current.computeStateHash(), name: "Renamed")
        let outcome = await store.apply(remote: remote, updaterMemberId: alice.memberId)
        guard case .applied(let applied) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(applied.removedMemberIds, [dave.memberId])
        XCTAssertEqual(applied.groupName, "Renamed")
    }

    func test_store_refusesStateThatSilentlyDropsThisDevice_F26() async throws {
        let alice = try identity("alice"), bob = try identity("bob")
        let current = group([member(alice, "A"), member(bob, "B")], creator: alice, version: 2)
        let persistence = InMemoryGroupStatePersistence(initial: current)
        let store = GroupStateStore(localMemberId: bob.memberId, persistence: persistence)
        try await store.load()
        // A stale creator roster without Bob and with no tombstone for him.
        let stale = group([member(alice, "A")], creator: alice, version: 4)
        let outcome = await store.apply(remote: stale, updaterMemberId: alice.memberId)
        XCTAssertEqual(outcome, .refusedMissingSelf)
        let afterCurrent = await store.current
        XCTAssertEqual(afterCurrent, current)
        XCTAssertEqual(try persistence.load(), current)
    }

    func test_store_reportsRemovalWhenAnAuthorizedTombstoneEndsOurMembership() async throws {
        let alice = try identity("alice"), bob = try identity("bob")
        let current = group([member(alice, "A"), member(bob, "B")], creator: alice, version: 2)
        let store = GroupStateStore(localMemberId: bob.memberId, persistence: InMemoryGroupStatePersistence(initial: current))
        try await store.load()
        let removal = group([member(alice, "A")], creator: alice, version: 3, previous: current.computeStateHash(), removed: [bob.memberId])
        guard case .removed = await store.apply(remote: removal, updaterMemberId: alice.memberId) else { return XCTFail() }
    }

    func test_store_reconcilesDivergedBranchesInsteadOfRejecting() async throws {
        let alice = try identity("alice"), bob = try identity("bob"), carol = try identity("carol"), dave = try identity("dave")
        let base = group([member(alice, "A"), member(bob, "B")], creator: alice, version: 2)
        // We (Bob) added Carol at v3; Alice meanwhile added Dave at v3 then renamed at v4.
        let ours = group([member(alice, "A"), member(bob, "B"), member(carol, "C")], creator: alice, version: 3, previous: base.computeStateHash())
        let theirs3 = group([member(alice, "A"), member(bob, "B"), member(dave, "D")], creator: alice, version: 3, previous: base.computeStateHash())
        let theirs4 = group(theirs3.members, creator: alice, version: 4, previous: theirs3.computeStateHash(), name: "New")
        let store = GroupStateStore(localMemberId: bob.memberId, persistence: InMemoryGroupStatePersistence(initial: ours))
        try await store.load()
        guard case .applied(let merged) = await store.apply(remote: theirs4, updaterMemberId: alice.memberId) else { return XCTFail() }
        XCTAssertEqual(Set(merged.members.map(\.memberId)), [alice.memberId, bob.memberId, carol.memberId, dave.memberId])
        XCTAssertEqual(merged.version, 5)
    }

    func test_filePersistence_unreadableFileThrowsAndIsNotDeleted_F14() throws {
        struct FixedKey: GroupStateKeyProvider { let k: SymmetricKey; func key() throws -> SymmetricKey { k } }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let url = dir.appendingPathComponent("group.bin")
        let alice = try identity("alice")
        let g = group([member(alice, "A")], creator: alice, version: 1)
        try FileGroupStatePersistence(fileURL: url, keyProvider: FixedKey(k: SymmetricKey(size: .bits256))).save(g)
        // A different key: the file can't be opened. That must surface, not read as "no group".
        let other = FileGroupStatePersistence(fileURL: url, keyProvider: FixedKey(k: SymmetricKey(size: .bits256)))
        XCTAssertThrowsError(try other.load()) { XCTAssertEqual($0 as? GroupStatePersistenceError, .unreadable) }
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        try? FileManager.default.removeItem(at: dir)
    }

    // MARK: - Engine, end to end

    private func pair() async throws -> (alice: FamilySafeIdentity, bob: FamilySafeIdentity, base: GroupDefinition,
                                          aliceEngine: GroupSyncEngine, bobEngine: GroupSyncEngine,
                                          aliceStore: GroupStateStore, bobStore: GroupStateStore,
                                          aliceOut: CapturingOutbox, bobOut: CapturingOutbox) {
        let alice = try identity("alice"), bob = try identity("bob")
        let base = group([member(alice, "A"), member(bob, "B")], creator: alice, version: 2)
        let aliceStore = GroupStateStore(localMemberId: alice.memberId, persistence: InMemoryGroupStatePersistence(initial: base))
        let bobStore = GroupStateStore(localMemberId: bob.memberId, persistence: InMemoryGroupStatePersistence(initial: base))
        try await aliceStore.load(); try await bobStore.load()
        let aliceOut = CapturingOutbox(), bobOut = CapturingOutbox()
        let clock: @Sendable () -> Int64 = { 1_800_000_000_000 }
        return (alice, bob, base,
                GroupSyncEngine(identity: alice, store: aliceStore, outbox: aliceOut, now: clock),
                GroupSyncEngine(identity: bob, store: bobStore, outbox: bobOut, now: clock),
                aliceStore, bobStore, aliceOut, bobOut)
    }

    func test_engine_broadcastIsAppliedByPeerAndAcked() async throws {
        let p = try await pair()
        let renamed = p.base.with(groupName: "Renamed", version: 3, previousStateHash: .some(p.base.computeStateHash()))
        await p.aliceEngine.broadcast(renamed, changeType: .nameChanged)
        let toBob = await p.aliceOut.to(Topics.groupSyncInbox(memberId: p.bob.memberId))
        XCTAssertEqual(toBob.count, 1)
        let event = await p.bobEngine.handleGroupSync(payload: toBob[0])
        XCTAssertEqual(event, .outcome(.applied(renamed)))
        let bobCurrent = await p.bobStore.current
        XCTAssertEqual(bobCurrent?.groupName, "Renamed")
        let acks = await p.bobOut.to(Topics.groupAck(groupId: "g-1"))
        XCTAssertEqual(acks.count, 1)
    }

    func test_engine_ignoresUpdateWrappedByAnotherMember_F25() async throws {
        let p = try await pair()
        let carol = try identity("carol")
        // Carol (a member) wraps a message that claims Alice as updater in her own envelope.
        let members = p.base.members + [member(carol, "C")]
        let base = p.base.with(members: members)
        let bobStore = GroupStateStore(localMemberId: p.bob.memberId, persistence: InMemoryGroupStatePersistence(initial: base))
        try await bobStore.load()
        let bobEngine = GroupSyncEngine(identity: p.bob, store: bobStore, outbox: p.bobOut, now: { 1 })
        let message = GroupSyncMessage(groupId: "g-1", version: 3, groupDefinition: base.with(version: 3),
                                       updaterMemberId: p.alice.memberId, changeType: .fullSync, timestamp: 1, signature: "00")
        let carolCrypto = E2EECrypto(localMemberId: carol.memberId, localEd25519SecretKey: carol.ed25519SecretKey64,
                                     localX25519SecretKey: carol.x25519PrivateKey)
        let envelope = try carolCrypto.encrypt(plaintext: String(data: try JSONEncoder().encode(message), encoding: .utf8)!,
                                               recipientMemberId: p.bob.memberId, recipientX25519PublicKey: p.bob.x25519PublicKey)
        let event = await bobEngine.handleGroupSync(payload: envelope)
        XCTAssertEqual(event, .ignored("envelope sender is not the updater"))
    }

    func test_engine_refreshRequests_membersOnly_andRateLimited_F16() async throws {
        let p = try await pair()
        let stranger = try identity("stranger")
        func request(from id: String) throws -> Data {
            try JSONEncoder().encode(GroupStateRefreshRequest(groupId: "g-1", requesterMemberId: id, minimumVersion: 1, reason: "x", timestamp: 1))
        }
        let strangerEvent = await p.aliceEngine.handleRefreshRequest(payload: try request(from: stranger.memberId))
        XCTAssertEqual(strangerEvent, .ignored("requester not a member"))
        let first = await p.aliceEngine.handleRefreshRequest(payload: try request(from: p.bob.memberId))
        let second = await p.aliceEngine.handleRefreshRequest(payload: try request(from: p.bob.memberId))
        let third = await p.aliceEngine.handleRefreshRequest(payload: try request(from: p.bob.memberId))
        XCTAssertEqual(first, .answeredRefresh(requester: p.bob.memberId))
        XCTAssertEqual(second, .answeredRefresh(requester: p.bob.memberId))
        XCTAssertEqual(third, .refreshRateLimited(requester: p.bob.memberId))
    }

    func test_engine_replayedOldUpdate_rebroadcastsAtMostOncePerMinute_F16() async throws {
        let p = try await pair()
        // Bob is ahead at v3; Alice's old v2 broadcast is replayed at him repeatedly.
        let ahead = p.base.with(groupName: "Ahead", version: 3, previousStateHash: .some(p.base.computeStateHash()))
        _ = await p.bobStore.apply(remote: ahead, updaterMemberId: p.alice.memberId)
        await p.aliceEngine.broadcast(p.base, changeType: .versionSync)
        let old = await p.aliceOut.to(Topics.groupSyncInbox(memberId: p.bob.memberId))[0]
        for _ in 0..<5 { _ = await p.bobEngine.handleGroupSync(payload: old) }
        let rebroadcasts = await p.bobOut.to(Topics.groupSyncInbox(memberId: p.alice.memberId))
        XCTAssertEqual(rebroadcasts.count, 1)
    }
}
