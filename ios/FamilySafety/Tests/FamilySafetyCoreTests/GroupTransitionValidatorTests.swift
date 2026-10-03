import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// One test per §7.3 rejection rule (a failing-input case that must throw), plus the
/// positive paths each rule carves out (self-removal, creator removal, quorum removal,
/// sibling reconciliation, creator transfer). Per IOS_PORT_SPEC.md §14 Phase 1, this file
/// plus the hash vector in VectorTests.swift is the acceptance bar for the phase.
final class GroupTransitionValidatorTests: XCTestCase {

    // Deterministic 64-byte "seeds" for test identities — not real BIP-39 seeds, just
    // 64 bytes each, which is all FamilySafeKeyDerivation.deriveIdentity(seed:) requires.
    private func identity(_ tag: String) throws -> FamilySafeIdentity {
        let seed = Array(SHA512.hash(data: Data(tag.utf8)))
        return try FamilySafeKeyDerivation.deriveIdentity(seed: seed)
    }

    private func member(_ id: FamilySafeIdentity, name: String, addedAt: Int64 = 1_700_000_000_000) -> FamilyMember {
        FamilyMember(
            memberId: id.memberId,
            displayName: name,
            ed25519PublicKey: id.ed25519PublicKeyHex,
            x25519PublicKey: id.x25519PublicKeyHex,
            addedAtEpochMs: addedAt
        )
    }

    private let groupId = "test-group-0000"
    private let createdAt: Int64 = 1_700_000_000_000

    private func group(
        members: [FamilyMember],
        creator: FamilySafeIdentity,
        version: Int64,
        previousStateHash: String? = nil,
        removedMemberIds: [String] = [],
        groupName: String = "Test Family"
    ) -> GroupDefinition {
        GroupDefinition(
            groupId: groupId,
            groupName: groupName,
            createdAtEpochMs: createdAt,
            creatorMemberId: creator.memberId,
            members: members,
            version: version,
            previousStateHash: previousStateHash,
            removedMemberIds: removedMemberIds
        )
    }

    private func quorumVote(voter: FamilySafeIdentity, target: String, groupId: String, proposedVersion: Int64, previousStateHash: String) throws -> QuorumVote {
        let payload = RemovalVoteMessage.signingPayload(
            groupId: groupId, targetMemberId: target, proposedVersion: proposedVersion, previousStateHash: previousStateHash
        )
        let sig = try SodiumRaw.signDetached(message: Array(payload.utf8), secretKey: voter.ed25519SecretKey64)
        return QuorumVote(voterMemberId: voter.memberId, signatureHex: Hex.encode(sig))
    }

    // MARK: - Happy path: ordinary member addition by the creator

    func test_acceptsValidSuccessor_memberAdded() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice")], creator: alice, version: 1)
        let remote = group(
            members: [member(alice, name: "Alice"), member(bob, name: "Bob")],
            creator: alice,
            version: 2,
            previousStateHash: current.computeStateHash()
        )
        XCTAssertNoThrow(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId))
    }

    // MARK: - Immutable fields

    func test_rejectsGroupIdChanged() throws {
        let alice = try identity("alice")
        let current = group(members: [member(alice, name: "Alice")], creator: alice, version: 1)
        var remote = current
        remote = GroupDefinition(
            groupId: "different-group-id", groupName: remote.groupName, createdAtEpochMs: remote.createdAtEpochMs,
            creatorMemberId: remote.creatorMemberId, members: remote.members, version: remote.version + 1,
            previousStateHash: current.computeStateHash()
        )
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .immutableFieldChanged)
        }
    }

    // MARK: - Updater membership

    func test_rejectsUpdaterNotInRoster() throws {
        let alice = try identity("alice")
        let stranger = try identity("stranger")
        let current = group(members: [member(alice, name: "Alice")], creator: alice, version: 1)
        let remote = group(members: [member(alice, name: "Alice")], creator: alice, version: 2, previousStateHash: current.computeStateHash())
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: stranger.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .updaterNotInRoster)
        }
    }

    // MARK: - Hash chain

    func test_rejectsChainMismatch() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice")], creator: alice, version: 1)
        let remote = group(
            members: [member(alice, name: "Alice"), member(bob, name: "Bob")],
            creator: alice, version: 2, previousStateHash: "0000not_the_real_hash"
        )
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .chainMismatch)
        }
    }

    // MARK: - No key rotation

    func test_rejectsKeyRotation() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let impostor = try identity("impostor")
        let current = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob")], creator: alice, version: 1)
        var rotatedBob = member(bob, name: "Bob")
        rotatedBob = FamilyMember(
            memberId: bob.memberId, displayName: "Bob",
            ed25519PublicKey: impostor.ed25519PublicKeyHex, x25519PublicKey: impostor.x25519PublicKeyHex,
            addedAtEpochMs: 1_700_000_000_000
        )
        let remote = group(members: [member(alice, name: "Alice"), rotatedBob], creator: alice, version: 2, previousStateHash: current.computeStateHash())
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .memberKeyRotation)
        }
    }

    // MARK: - Added member must self-authenticate

    func test_rejectsAddedMemberIdMismatch() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice")], creator: alice, version: 1)
        let forgedBob = FamilyMember(
            memberId: "0000000000000000000000000000000", displayName: "Bob",
            ed25519PublicKey: bob.ed25519PublicKeyHex, x25519PublicKey: bob.x25519PublicKeyHex,
            addedAtEpochMs: 1_700_000_000_000
        )
        let remote = group(members: [member(alice, name: "Alice"), forgedBob], creator: alice, version: 2, previousStateHash: current.computeStateHash())
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .addedMemberIdMismatch)
        }
    }

    // MARK: - Tombstones are append-only

    func test_rejectsDroppedTombstone() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice")], creator: alice, version: 2, removedMemberIds: [bob.memberId])
        let remote = group(members: [member(alice, name: "Alice")], creator: alice, version: 3, previousStateHash: current.computeStateHash(), removedMemberIds: [])
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .tombstoneDropped)
        }
    }

    // MARK: - Present and removed are mutually exclusive

    func test_rejectsMemberBothPresentAndRemoved() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob")], creator: alice, version: 1)
        let remote = group(
            members: [member(alice, name: "Alice"), member(bob, name: "Bob")],
            creator: alice, version: 2, previousStateHash: current.computeStateHash(), removedMemberIds: [bob.memberId]
        )
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .memberBothPresentAndRemoved)
        }
    }

    // MARK: - Silent removal (successor) vs. sibling exemption

    func test_rejectsSilentRemovalOnSuccessor() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob")], creator: alice, version: 1)
        // Bob just vanishes — no tombstone recorded.
        let remote = group(members: [member(alice, name: "Alice")], creator: alice, version: 2, previousStateHash: current.computeStateHash())
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .unauthorizedMemberRemoval)
        }
    }

    func test_acceptsUntombstonedGapOnSibling() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        // Both at version 2, sharing the same parent — this peer hasn't learned about Bob yet.
        let current = group(members: [member(alice, name: "Alice")], creator: alice, version: 2)
        let remote = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob")], creator: alice, version: 2)
        XCTAssertNoThrow(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId))
    }

    // MARK: - Removal authorization: creator, self, or quorum

    func test_acceptsCreatorRemoval() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob")], creator: alice, version: 1)
        let remote = group(
            members: [member(alice, name: "Alice")], creator: alice, version: 2,
            previousStateHash: current.computeStateHash(), removedMemberIds: [bob.memberId]
        )
        XCTAssertNoThrow(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId))
    }

    func test_acceptsSelfRemoval() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob")], creator: alice, version: 1)
        let remote = group(
            members: [member(alice, name: "Alice")], creator: alice, version: 2,
            previousStateHash: current.computeStateHash(), removedMemberIds: [bob.memberId]
        )
        // Bob is the updater, removing only himself — allowed without being the creator.
        XCTAssertNoThrow(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: bob.memberId))
    }

    func test_rejectsNonCreatorRemovalWithoutQuorum() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let carol = try identity("carol")
        let current = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob"), member(carol, name: "Carol")], creator: alice, version: 1)
        let remote = group(
            members: [member(alice, name: "Alice"), member(carol, name: "Carol")], creator: alice, version: 2,
            previousStateHash: current.computeStateHash(), removedMemberIds: [bob.memberId]
        )
        // Carol proposes removing Bob with zero votes — not creator, not self, no quorum.
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: carol.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .unauthorizedNewTombstone)
        }
    }

    func test_acceptsQuorumRemoval() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")   // target
        let carol = try identity("carol")
        let dave = try identity("dave")
        let erin = try identity("erin")
        let current = group(
            members: [member(alice, name: "Alice"), member(bob, name: "Bob"), member(carol, name: "Carol"), member(dave, name: "Dave"), member(erin, name: "Erin")],
            creator: alice, version: 1
        )
        // remaining = 4 (excludes Bob), needed = 4/2+1 = 3.
        let proposedVersion: Int64 = 2
        let prevHash = current.computeStateHash()
        let votes = try [
            quorumVote(voter: carol, target: bob.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash),
            quorumVote(voter: dave, target: bob.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash),
            quorumVote(voter: erin, target: bob.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash),
        ]
        let remote = group(
            members: [member(alice, name: "Alice"), member(carol, name: "Carol"), member(dave, name: "Dave"), member(erin, name: "Erin")],
            creator: alice, version: proposedVersion, previousStateHash: prevHash, removedMemberIds: [bob.memberId]
        )
        // Carol broadcasts having reached quorum — she is neither creator nor the target.
        XCTAssertNoThrow(try GroupTransitionValidator.validate(
            current: current, remote: remote, updaterMemberId: carol.memberId,
            quorumVotes: [bob.memberId: votes]
        ))
    }

    func test_rejectsQuorumRemovalInsufficientVotes() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let carol = try identity("carol")
        let dave = try identity("dave")
        let erin = try identity("erin")
        let current = group(
            members: [member(alice, name: "Alice"), member(bob, name: "Bob"), member(carol, name: "Carol"), member(dave, name: "Dave"), member(erin, name: "Erin")],
            creator: alice, version: 1
        )
        let proposedVersion: Int64 = 2
        let prevHash = current.computeStateHash()
        // Only 2 votes; 3 are needed.
        let votes = try [
            quorumVote(voter: carol, target: bob.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash),
            quorumVote(voter: dave, target: bob.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash),
        ]
        let remote = group(
            members: [member(alice, name: "Alice"), member(carol, name: "Carol"), member(dave, name: "Dave"), member(erin, name: "Erin")],
            creator: alice, version: proposedVersion, previousStateHash: prevHash, removedMemberIds: [bob.memberId]
        )
        XCTAssertThrowsError(try GroupTransitionValidator.validate(
            current: current, remote: remote, updaterMemberId: carol.memberId,
            quorumVotes: [bob.memberId: votes]
        )) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .unauthorizedNewTombstone)
        }
    }

    // MARK: - Rename is creator-only

    func test_rejectsRenameByNonCreator() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob")], creator: alice, version: 1)
        let remote = group(
            members: [member(alice, name: "Alice"), member(bob, name: "Bob")], creator: alice, version: 2,
            previousStateHash: current.computeStateHash(), groupName: "Renamed"
        )
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: bob.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .unauthorizedRename)
        }
    }

    func test_acceptsRenameByCreator() throws {
        let alice = try identity("alice")
        let current = group(members: [member(alice, name: "Alice")], creator: alice, version: 1)
        let remote = group(
            members: [member(alice, name: "Alice")], creator: alice, version: 2,
            previousStateHash: current.computeStateHash(), groupName: "Renamed"
        )
        XCTAssertNoThrow(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId))
    }

    // MARK: - Creator transfer (§7.5 voluntary path)

    func test_acceptsVoluntaryCreatorTransfer() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob")], creator: alice, version: 1)
        let remote = GroupDefinition(
            groupId: groupId, groupName: current.groupName, createdAtEpochMs: createdAt,
            creatorMemberId: bob.memberId, members: current.members, version: 2,
            previousStateHash: current.computeStateHash()
        )
        XCTAssertNoThrow(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId))
    }

    func test_rejectsCreatorChangeByNonCreator() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let current = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob")], creator: alice, version: 1)
        let remote = GroupDefinition(
            groupId: groupId, groupName: current.groupName, createdAtEpochMs: createdAt,
            creatorMemberId: bob.memberId, members: current.members, version: 2,
            previousStateHash: current.computeStateHash()
        )
        // Bob declares himself creator without Alice's (the actual creator's) signature.
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: bob.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .unauthorizedCreatorChange)
        }
    }

    // MARK: - Creator succession on quorum removal (§7.5 point 7)

    func test_acceptsQuorumCreatorRemovalWithCorrectSuccessor() throws {
        let alice = try identity("alice") // creator, target
        let bob = try identity("bob")
        let carol = try identity("carol")
        let dave = try identity("dave")
        let erin = try identity("erin")
        let current = group(
            members: [
                member(alice, name: "Alice", addedAt: 1000),
                member(bob, name: "Bob", addedAt: 2000),
                member(carol, name: "Carol", addedAt: 3000),
                member(dave, name: "Dave", addedAt: 4000),
                member(erin, name: "Erin", addedAt: 5000)
            ],
            creator: alice, version: 1
        )
        // remaining = 4 (excludes Alice), needed = 4/2+1 = 3.
        let proposedVersion: Int64 = 2
        let prevHash = current.computeStateHash()
        let votes = try [
            quorumVote(voter: bob, target: alice.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash),
            quorumVote(voter: carol, target: alice.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash),
            quorumVote(voter: dave, target: alice.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash)
        ]
        // Successor = smallest (addedAtEpochMs, memberId) among the survivors -> Bob (2000).
        let remote = GroupDefinition(
            groupId: groupId, groupName: current.groupName, createdAtEpochMs: createdAt,
            creatorMemberId: bob.memberId,
            members: [member(bob, name: "Bob", addedAt: 2000), member(carol, name: "Carol", addedAt: 3000), member(dave, name: "Dave", addedAt: 4000), member(erin, name: "Erin", addedAt: 5000)],
            version: proposedVersion, previousStateHash: prevHash, removedMemberIds: [alice.memberId]
        )
        XCTAssertNoThrow(try GroupTransitionValidator.validate(
            current: current, remote: remote, updaterMemberId: carol.memberId,
            quorumVotes: [alice.memberId: votes]
        ))
    }

    func test_rejectsQuorumCreatorRemovalWithWrongSuccessor() throws {
        let alice = try identity("alice") // creator, target
        let bob = try identity("bob")
        let carol = try identity("carol")
        let dave = try identity("dave")
        let erin = try identity("erin")
        let current = group(
            members: [
                member(alice, name: "Alice", addedAt: 1000),
                member(bob, name: "Bob", addedAt: 2000),
                member(carol, name: "Carol", addedAt: 3000),
                member(dave, name: "Dave", addedAt: 4000),
                member(erin, name: "Erin", addedAt: 5000)
            ],
            creator: alice, version: 1
        )
        let proposedVersion: Int64 = 2
        let prevHash = current.computeStateHash()
        let votes = try [
            quorumVote(voter: bob, target: alice.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash),
            quorumVote(voter: carol, target: alice.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash),
            quorumVote(voter: dave, target: alice.memberId, groupId: groupId, proposedVersion: proposedVersion, previousStateHash: prevHash)
        ]
        // Wrong successor: should be Bob (smallest addedAtEpochMs among survivors), not Carol.
        let remote = GroupDefinition(
            groupId: groupId, groupName: current.groupName, createdAtEpochMs: createdAt,
            creatorMemberId: carol.memberId,
            members: [member(bob, name: "Bob", addedAt: 2000), member(carol, name: "Carol", addedAt: 3000), member(dave, name: "Dave", addedAt: 4000), member(erin, name: "Erin", addedAt: 5000)],
            version: proposedVersion, previousStateHash: prevHash, removedMemberIds: [alice.memberId]
        )
        XCTAssertThrowsError(try GroupTransitionValidator.validate(
            current: current, remote: remote, updaterMemberId: carol.memberId,
            quorumVotes: [alice.memberId: votes]
        )) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .unauthorizedCreatorChange)
        }
    }

    func test_rejectsCreatorTransferBundledWithTombstone() throws {
        let alice = try identity("alice")
        let bob = try identity("bob")
        let carol = try identity("carol")
        let current = group(members: [member(alice, name: "Alice"), member(bob, name: "Bob"), member(carol, name: "Carol")], creator: alice, version: 1)
        let remote = GroupDefinition(
            groupId: groupId, groupName: current.groupName, createdAtEpochMs: createdAt,
            creatorMemberId: bob.memberId, members: [member(alice, name: "Alice"), member(bob, name: "Bob")], version: 2,
            previousStateHash: current.computeStateHash(), removedMemberIds: [carol.memberId]
        )
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .unauthorizedCreatorChange)
        }
    }
}
