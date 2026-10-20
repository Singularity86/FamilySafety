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
    final class Clock: @unchecked Sendable { var now: Int64 = 1_800_000_000_000 }

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

    // MARK: - Protocol 4 (3.3 keyring, 3.4 sealed control messages)

    func test_keys_currentIsHighestEpochThenLowestKid_andRotationDue() throws {
        let alice = try identity("alice")
        let fileKey = String(repeating: "11", count: 32)
        var g = group([member(alice, "A")], creator: alice, version: 1).with(fileEncryptionKey: .some(fileKey))
        XCTAssertEqual(GroupKeys.current(g)?.epoch, 0)
        XCTAssertNil(GroupKeys.stamp(g))
        XCTAssertFalse(GroupKeys.rotationDue(g))
        g = g.with(removedMemberIds: ["x"])
        XCTAssertTrue(GroupKeys.rotationDue(g))
        let k1 = try GroupKeys.newKey(epoch: 1), k2 = try GroupKeys.newKey(epoch: 1)
        g = g.with(groupKeys: [k1, k2])
        XCTAssertEqual(GroupKeys.current(g)?.kid, min(k1.kid, k2.kid))
        XCTAssertEqual(GroupKeys.stamp(g), min(k1.kid, k2.kid))
        XCTAssertFalse(GroupKeys.rotationDue(g))
        XCTAssertEqual(GroupKeys.keyHex(g, kid: nil), fileKey)
        XCTAssertEqual(GroupKeys.keyHex(g, kid: k2.kid.uppercased()), k2.key)
        XCTAssertNil(GroupKeys.keyHex(g, kid: "00000000deadbeef"))
    }

    func test_keys_rejectsForgedChangedOrPrematureEntries() throws {
        let alice = try identity("alice")
        let k1 = try GroupKeys.newKey(epoch: 1)
        let current = group([member(alice, "A")], creator: alice, version: 1).with(removedMemberIds: ["x"], groupKeys: [k1])
        XCTAssertFalse(GroupKeys.rejects(current: current, remote: current))
        XCTAssertTrue(GroupKeys.rejects(current: current, remote: current.with(groupKeys: [GroupKey(kid: k1.kid, epoch: 2, key: k1.key)])))
        let other = try GroupKeys.newKey(epoch: 1)
        XCTAssertTrue(GroupKeys.rejects(current: current, remote: current.with(groupKeys: [k1, GroupKey(kid: k1.kid, epoch: 1, key: other.key)])))
        XCTAssertTrue(GroupKeys.rejects(current: current, remote: current.with(groupKeys: [k1, try GroupKeys.newKey(epoch: 2)])))
        XCTAssertFalse(GroupKeys.rejects(current: current, remote: current.with(groupKeys: [k1, other])))
    }

    func test_keys_gateWaitsForEveryoneCountedAtFour() {
        let day: Int64 = 86_400_000
        let now = 100 * day
        let ok = GroupKeys.everyoneAtLeast(memberIds: ["me", "a", "b"], selfId: "me",
                                           sightings: ["a": PeerSighting(protocolVersion: 4, seenAtMs: now - day),
                                                       "b": PeerSighting(protocolVersion: 3, seenAtMs: now - 31 * day)],
                                           observingSinceMs: now - 40 * day, nowMs: now, required: 4)
        XCTAssertTrue(ok, "b is silent past 30 days, so it no longer counts")
        XCTAssertFalse(GroupKeys.everyoneAtLeast(memberIds: ["me", "a"], selfId: "me",
                                                 sightings: ["a": PeerSighting(protocolVersion: 3, seenAtMs: now)],
                                                 observingSinceMs: 0, nowMs: now, required: 4))
        XCTAssertFalse(GroupKeys.everyoneAtLeast(memberIds: ["me", "a"], selfId: "me", sightings: [:],
                                                 observingSinceMs: now - day, nowMs: now, required: 4),
                       "never heard from, and not yet watching 30 days")
    }

    func test_presence_sealedUnderRotatedKeyCarriesKid_andUnknownKidIsReported() throws {
        let alice = try identity("alice")
        let fileKey = String(repeating: "22", count: 32)
        let k1 = try GroupKeys.newKey(epoch: 1)
        let rotated = group([member(alice, "A")], creator: alice, version: 2)
            .with(fileEncryptionKey: .some(fileKey), removedMemberIds: ["x"], groupKeys: [k1])
        let payload = try PresenceCodec.encode(identity: alice, isOnline: true, timestamp: 5, group: rotated)
        XCTAssertEqual(try JSONDecoder().decode(SealedPresence.self, from: payload).kid, k1.kid)
        guard case .status(let s) = PresenceCodec.read(payload, sender: member(alice, "A"), group: rotated) else { return XCTFail() }
        XCTAssertEqual(s.protocolVersion, ProtocolVersion.current)
        let behind = rotated.with(groupKeys: [])
        XCTAssertEqual(PresenceCodec.read(payload, sender: member(alice, "A"), group: behind), .unknownKey)
        // Unrotated: no kid on the wire, exactly the bytes older builds expect.
        let plain = try PresenceCodec.encode(identity: alice, isOnline: true, timestamp: 5, group: behind)
        XCTAssertFalse(String(decoding: plain, as: UTF8.self).contains("kid"))
    }

    func test_store_rotatesOnlyWhenDueAndGateOpen() async throws {
        let alice = try identity("alice")
        let base = group([member(alice, "A")], creator: alice, version: 3)
            .with(fileEncryptionKey: .some(String(repeating: "33", count: 32)), removedMemberIds: ["gone"])
        let store = GroupStateStore(localMemberId: alice.memberId, persistence: InMemoryGroupStatePersistence(initial: base))
        try await store.load()
        let closed = try await store.rotateKeyIfDue(gateOpen: { _ in false })
        XCTAssertNil(closed)
        let rotated = try await store.rotateKeyIfDue(gateOpen: { _ in true })
        XCTAssertEqual(rotated?.version, 4)
        XCTAssertEqual(rotated?.groupKeys.map(\.epoch), [1])
        let again = try await store.rotateKeyIfDue(gateOpen: { _ in true })
        XCTAssertNil(again)
    }

    func test_engine_atProtocol4_sealsAcksAndRequests_andRefusesReplays() async throws {
        let p = try await pair()
        await p.aliceEngine.recordSighting(memberId: p.bob.memberId, protocolVersion: 4)
        await p.bobEngine.recordSighting(memberId: p.alice.memberId, protocolVersion: 4)
        let renamed = p.base.with(groupName: "Renamed", version: 3, previousStateHash: .some(p.base.computeStateHash()))
        await p.aliceEngine.broadcast(renamed, changeType: .nameChanged)
        let toBob = await p.aliceOut.to(Topics.groupSyncInbox(memberId: p.bob.memberId))
        let first = await p.bobEngine.handleGroupSync(payload: toBob[0])
        XCTAssertEqual(first, .outcome(.applied(renamed)))
        // Replaying the same sealed update is refused, not applied again or treated as a decrypt failure.
        let replay = await p.bobEngine.handleGroupSync(payload: toBob[0])
        XCTAssertEqual(replay, .refused(.replay))
        // The ack goes sealed to Alice's own inbox, not the group-wide topic.
        let plainAcks = await p.bobOut.to(Topics.groupAck(groupId: "g-1"))
        XCTAssertTrue(plainAcks.isEmpty)
        let sealedAcks = await p.bobOut.to(Topics.groupAckInbox(memberId: p.alice.memberId))
        XCTAssertEqual(sealedAcks.count, 1)
        await p.aliceEngine.handleAck(payload: sealedAcks[0])
        let acked = await p.aliceEngine.acks[3]
        XCTAssertEqual(acked, [p.bob.memberId])
        // Once Bob has sequenced to Alice, a plaintext ack or request in his name is refused.
        let forgedAck = try JSONEncoder().encode(GroupUpdateAck(groupId: "g-1", version: 9, memberId: p.bob.memberId, timestamp: 1))
        await p.aliceEngine.handleAck(payload: forgedAck)
        let forged = await p.aliceEngine.acks[9]
        XCTAssertNil(forged)
        let forgedRequest = try JSONEncoder().encode(GroupStateRefreshRequest(groupId: "g-1", requesterMemberId: p.bob.memberId,
                                                                             minimumVersion: 1, reason: "x", timestamp: 1))
        let refused = await p.aliceEngine.handleRefreshRequest(payload: forgedRequest)
        XCTAssertEqual(refused, .ignored("unauthenticated or malformed refresh request"))
        // A sealed request from Bob opens and is judged on its merits (Alice is still at v2,
        // Bob asks for v3, so she has nothing newer to send).
        await p.bobEngine.requestRefresh(reason: "test")
        let requests = await p.bobOut.to(Topics.syncRequest(memberId: p.alice.memberId))
        XCTAssertNotNil(EncryptedEnvelope.peekSenderMemberId(json: requests[0]))
        let judged = await p.aliceEngine.handleRefreshRequest(payload: requests[0])
        XCTAssertEqual(judged, .ignored("we are behind too"))
    }

    func test_engine_downgradeAfterSequencedIsRefusedAndSurfaced() async throws {
        let p = try await pair()
        await p.aliceEngine.recordSighting(memberId: p.bob.memberId, protocolVersion: 4)
        let v3 = p.base.with(groupName: "One", version: 3, previousStateHash: .some(p.base.computeStateHash()))
        await p.aliceEngine.broadcast(v3, changeType: .nameChanged)
        let sealed = await p.aliceOut.to(Topics.groupSyncInbox(memberId: p.bob.memberId))
        _ = await p.bobEngine.handleGroupSync(payload: sealed[0])
        // Alice's next update arrives unsequenced (a phone gone back to an older build, or a replay).
        let legacyAlice = GroupSyncEngine(identity: p.alice, store: p.aliceStore, outbox: p.aliceOut, now: { 1_800_000_000_000 })
        let v4 = v3.with(groupName: "Two", version: 4, previousStateHash: .some(v3.computeStateHash()))
        await legacyAlice.broadcast(v4, changeType: .nameChanged)
        let bare = await p.aliceOut.to(Topics.groupSyncInbox(memberId: p.bob.memberId))
        let event = await p.bobEngine.handleGroupSync(payload: bare[1])
        XCTAssertEqual(event, .refused(.unsequencedAfterSequenced))
        let outdated = await p.bobEngine.outdatedMembers(v3)
        XCTAssertEqual(outdated, [p.alice.memberId])
    }

    func test_engine_ledgerPersistsAcrossRestart() async throws {
        let p = try await pair()
        let slot = InMemorySlot()
        let first = GroupSyncEngine(identity: p.alice, store: p.aliceStore, outbox: p.aliceOut, ledgerSlot: slot, now: { 1_800_000_000_000 })
        await first.recordSighting(memberId: p.bob.memberId, protocolVersion: 4)
        await first.broadcast(p.base, changeType: .fullSync)
        let seqBefore = await first.ledger.counter.last
        let second = GroupSyncEngine(identity: p.alice, store: p.aliceStore, outbox: p.aliceOut, ledgerSlot: slot, now: { 1 })
        let restored = await second.ledger
        XCTAssertEqual(restored.sightings[p.bob.memberId]?.protocolVersion, 4)
        XCTAssertEqual(restored.counter.last, seqBefore)
        XCTAssertGreaterThan(seqBefore, 0)
    }

    func test_engine_unknownSenderAsksForTheRosterOncePer5Minutes_49() async throws {
        let p = try await pair()
        let carol = try identity("carol")
        // Carol joined while Bob was away: Bob's roster doesn't have her yet.
        let carolCrypto = E2EECrypto(localMemberId: carol.memberId, localEd25519SecretKey: carol.ed25519SecretKey64,
                                     localX25519SecretKey: carol.x25519PrivateKey)
        let envelope = try carolCrypto.encrypt(plaintext: "{}", recipientMemberId: p.bob.memberId, recipientX25519PublicKey: p.bob.x25519PublicKey)
        let first = await p.bobEngine.handleGroupSync(payload: envelope)
        XCTAssertEqual(first, .ignored("sender not in our roster"))
        _ = await p.bobEngine.handleGroupSync(payload: envelope)
        let requests = await p.bobOut.to(Topics.syncRequest(memberId: p.alice.memberId))
        XCTAssertEqual(requests.count, 1, "a known member answers; once per 5 minutes")
        // A removed member, or ourselves, never triggers it.
        await p.bobEngine.catchUpOnUnknownSender(p.bob.memberId)
        let after = await p.bobOut.to(Topics.syncRequest(memberId: p.alice.memberId))
        XCTAssertEqual(after.count, 1)
    }

    func test_engine_unknownSenderGivenUpOnceARosterAnswersWithoutThem() async throws {
        let alice = try identity("alice"), bob = try identity("bob"), stranger = try identity("stranger")
        let base = group([member(alice, "A"), member(bob, "B")], creator: alice, version: 2)
        let bobStore = GroupStateStore(localMemberId: bob.memberId, persistence: InMemoryGroupStatePersistence(initial: base))
        try await bobStore.load()
        let aliceStore = GroupStateStore(localMemberId: alice.memberId, persistence: InMemoryGroupStatePersistence(initial: base))
        try await aliceStore.load()
        let clock = Clock()
        let bobOut = CapturingOutbox(), aliceOut = CapturingOutbox()
        let bobEngine = GroupSyncEngine(identity: bob, store: bobStore, outbox: bobOut, now: { clock.now })
        let aliceEngine = GroupSyncEngine(identity: alice, store: aliceStore, outbox: aliceOut, now: { clock.now })
        await bobEngine.catchUpOnUnknownSender(stranger.memberId)
        // Alice answers with her roster, which doesn't have the stranger.
        await aliceEngine.broadcast(base, changeType: .fullSync)
        let reply = await aliceOut.to(Topics.groupSyncInbox(memberId: bob.memberId))
        _ = await bobEngine.handleGroupSync(payload: reply[0])
        clock.now += 6 * 60_000
        await bobEngine.catchUpOnUnknownSender(stranger.memberId)
        var asked = await bobOut.to(Topics.syncRequest(memberId: alice.memberId)).count
        XCTAssertEqual(asked, 1, "given up on for a day, though the 5-minute window has passed")
        clock.now += 24 * 60 * 60_000
        await bobEngine.catchUpOnUnknownSender(stranger.memberId)
        asked = await bobOut.to(Topics.syncRequest(memberId: alice.memberId)).count
        XCTAssertEqual(asked, 2, "asked again after a day")
    }

    func test_engine_refreshRequests_membersOnly_andRateLimited_F16() async throws {
        let p = try await pair()
        let stranger = try identity("stranger")
        func request(from id: String) throws -> Data {
            try JSONEncoder().encode(GroupStateRefreshRequest(groupId: "g-1", requesterMemberId: id, minimumVersion: 1, reason: "x", timestamp: 1))
        }
        let strangerEvent = await p.aliceEngine.handleRefreshRequest(payload: try request(from: stranger.memberId))
        XCTAssertEqual(strangerEvent, .ignored("unauthenticated or malformed refresh request"))
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
