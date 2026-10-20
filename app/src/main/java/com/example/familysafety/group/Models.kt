package com.example.familysafety.group

import com.example.familysafety.core.toHex
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * Key derivation path indices following SLIP-10 for Ed25519/X25519.
 * Base path: m/44'/1984'/accountIndex'/keyType'
 */
object KeyPaths {
    const val PURPOSE = 44
    const val COIN_TYPE = 1984  // Custom coin type for FamilySafe (unregistered)
    const val KEY_TYPE_SIGNING = 0     // .../0' → Ed25519 identity/signing
    const val KEY_TYPE_ENCRYPTION = 1  // .../1' → X25519 encryption
}

/**
 * Represents the connection state of a family member.
 */
sealed class ConnectionState {
    /** Direct peer-to-peer connection via local WiFi (NSD discovery). */
    data class Local(val localAddress: String, val port: Int) : ConnectionState()

    /** Connected via MQTT cloud relay. */
    data class Cloud(val mqttTopic: String) : ConnectionState()

    /** Member is offline; messages queued via MQTT v5 persistent session. */
    object Offline : ConnectionState()

    /** Connection state unknown (initial state). */
    object Unknown : ConnectionState()
}

/**
 * Presence status as reported by the member's device.
 */
@Serializable
enum class PresenceStatus {
    ACTIVE,      // Device recently active
    IDLE,        // Device idle but connected
    BACKGROUND,  // App in background, limited updates
    OFFLINE      // No connection
}

/**
 * Represents a single family member within the trusted group.
 *
 * @property memberId Unique identifier derived from public signing key: SHA-256(ed25519PubKey).take(16).toHex()
 * @property displayName Human-readable name chosen by the member
 * @property ed25519PublicKey 32-byte Ed25519 public key for signature verification (hex-encoded)
 * @property x25519PublicKey 32-byte X25519 public key for encryption (hex-encoded)
 * @property addedAtEpochMs Timestamp when member was added to family group
 * @property addedByMemberId The memberId of who invited this member (null for group creator)
 * @property joinEpoch Which membership this is for [memberId]: 0 the first time, one more for
 *   each time they have been removed (or left) and let back in. A recovery phrase always
 *   regenerates the same member ID, so the ID alone cannot tell a returning member from the
 *   one who was removed; removals are recorded per membership instead — see [Tombstones].
 */
@Serializable
data class FamilyMember(
    val memberId: String,
    val displayName: String,
    val ed25519PublicKey: String,
    val x25519PublicKey: String,
    val addedAtEpochMs: Long,
    val addedByMemberId: String? = null,
    val avatarHash: String? = null,
    val colorHue: Float? = null,   // 0–359; null = derive from memberId hash
    val joinEpoch: Int = 0,
    /** When this member last signed their profile (F25; see [ProfileGuard]). Not hashed. */
    val profileUpdatedAtMs: Long? = null,
    /** This member's own signature over [ProfileGuard.profileMessage]. Not hashed. */
    val profileSignature: String? = null
)

/**
 * Entries in [GroupDefinition.removedMemberIds]. A tombstone names one *membership*, not a
 * person: the bare member ID for the first membership (exactly what builds before memberships
 * existed wrote, so every existing tombstone keeps its meaning) and `"<memberId>#<n>"` for
 * membership n. A member is removed when the tombstone for their current membership exists;
 * letting them back in creates the next membership, which no tombstone names yet.
 *
 * Member IDs are hex, so `#` can never be part of one.
 */
object Tombstones {
    fun key(memberId: String, joinEpoch: Int): String =
        if (joinEpoch <= 0) memberId else "$memberId#$joinEpoch"

    fun key(member: FamilyMember): String = key(member.memberId, member.joinEpoch)

    fun memberIdOf(tombstone: String): String = tombstone.substringBefore('#')

    fun epochOf(tombstone: String): Int =
        if ('#' in tombstone) tombstone.substringAfter('#').toIntOrNull() ?: 0 else 0

    fun isRemoved(member: FamilyMember, tombstones: Set<String>): Boolean = key(member) in tombstones

    /** The membership to give [memberId] if they are let in now: one past their last removal. */
    fun nextJoinEpoch(memberId: String, tombstones: Set<String>): Int =
        tombstones.filter { memberIdOf(it) == memberId }.maxOfOrNull { epochOf(it) + 1 } ?: 0
}

/**
 * Signed evidence that a removal was authorized, kept with the tombstone it covers so that any
 * member can pass the removal on — not just whoever made it.
 *
 * Without one, a removal reached a phone only if that phone heard it from the creator (or from
 * the member who left) directly; repeated by anyone else it looked exactly like a forgery and
 * was refused. A member who left while some phones were unreachable stayed on those phones
 * for good, because the only device that could vouch for the departure had erased itself.
 *
 * @property tombstone the [Tombstones] key this proves
 * @property authorizerMemberId the creator, or the member themselves when they left
 * @property signature by [authorizerMemberId] over [buildRemovalProofMessage]
 */
@Serializable
data class RemovalProof(
    val tombstone: String,
    val authorizerMemberId: String,
    val signature: String
)

/**
 * What a [RemovalProof] signs. Bound to the group and to one membership, not to a version: a
 * tombstone is permanent, so the proof may be repeated at any later version, and a returning
 * member's new membership has a different key that an old proof does not cover. The prefix is
 * distinct from "REMOVE:" and "REMOVE_QUORUM:", so no signature made for one can be read as
 * another.
 */
fun buildRemovalProofMessage(groupId: String, tombstone: String): ByteArray =
    "REMOVAL_PROOF:v1:$groupId:$tombstone".toByteArray(Charsets.UTF_8)

/**
 * Runtime state for a family member (not persisted).
 */
data class MemberRuntimeState(
    val member: FamilyMember,
    val connectionState: ConnectionState = ConnectionState.Unknown,
    val presenceStatus: PresenceStatus = PresenceStatus.OFFLINE,
    val lastSeenEpochMs: Long? = null,
    val lastLocationUpdateEpochMs: Long? = null,
    val unacknowledgedMessageCount: Int = 0
)

/**
 * One member's signed vote toward removing another member without the creator's
 * cooperation. Carried alongside a [GroupDefinition] update (see `GroupSyncMessage`
 * in `sync/GroupSyncManager.kt`) once a quorum has been collected — never part of
 * [GroupDefinition] itself, so in-flight voting never touches the hash chain.
 *
 * @property signature over `"REMOVE_QUORUM:$groupId:$targetMemberId:$proposedVersion:$previousStateHash"`,
 *   verified against [voterMemberId]'s key in the *current* roster (never the incoming one).
 */
@Serializable
data class QuorumSignature(
    val voterMemberId: String,
    val signature: String
)

/**
 * Canonical message a quorum-removal vote signs. Shared by [GroupStateManager] (which
 * generates and re-verifies it) and `GroupSyncManager` (which verifies it independently
 * on receipt, both for individual votes and for the assembled removal it accompanies) —
 * one definition so the two can never silently drift apart. Prefixed distinctly from the
 * creator/self removal message ("REMOVE" vs "REMOVE_QUORUM") so a vote signature can never
 * be replayed as one of those or vice versa, and binds to [proposedVersion] and
 * [previousStateHash] so a vote cast for one proposal cannot authorize a different one.
 */
fun buildQuorumRemovalMessage(
    groupId: String,
    targetMemberId: String,
    proposedVersion: Long,
    previousStateHash: String
): ByteArray =
    "REMOVE_QUORUM:$groupId:$targetMemberId:$proposedVersion:$previousStateHash"
        .toByteArray(Charsets.UTF_8)

/**
 * The complete family group definition.
 * This is the persisted, signed group state that all members must agree upon.
 *
 * @property groupId Unique group identifier (UUID generated at creation)
 * @property groupName Display name for the family group
 * @property createdAtEpochMs Timestamp of group creation
 * @property creatorMemberId MemberId of the group creator
 * @property members Set of all family members (including creator)
 * @property version Monotonically increasing version for conflict resolution
 * @property previousStateHash Hash of previous group state (forms hash chain)
 * @property fileEncryptionKey Random 32-byte AES key (hex) shared by the group, used for
 *   shared files. Lives here because the definition already travels only inside the
 *   per-recipient encrypted envelope (join approval, group sync) and is persisted to the
 *   encrypted DataStore, so it is distributed and stored safely with no new plumbing.
 *   Null for groups created before this field existed; those fall back to the legacy
 *   groupId-derived key. Never log or display this value.
 * @property removedMemberIds Tombstones for members who have been removed. **Append-only.**
 *   Without these, merging two concurrently-edited states by unioning their rosters would
 *   resurrect anyone who had just been removed — the merge cannot otherwise tell "this peer
 *   has not heard about them yet" apart from "this peer removed them". Each entry ends one
 *   membership (see [Tombstones]), not the person: restoring from a recovery phrase brings back
 *   the same member ID, and a family must be able to let that person back in.
 * @property removalProofs Signed authority for tombstones, so that any member can relay a
 *   removal (see [RemovalProof]). Not covered by [computeStateHash]: each proof carries its own
 *   signature, and stripping one only takes away the ability to relay, never adds anything.
 */
@Serializable
data class GroupDefinition(
    val groupId: String,
    val groupName: String,
    val createdAtEpochMs: Long,
    val creatorMemberId: String,
    val members: Set<FamilyMember>,
    val version: Long,
    val previousStateHash: String? = null,
    val fileEncryptionKey: String? = null,
    val removedMemberIds: Set<String> = emptySet(),
    /** Whoever most recently confirmed the family's Play subscription is active. */
    val subscriberMemberId: String? = null,
    /**
     * When [subscriberMemberId] last checked with Play and found the subscription active.
     * Every device treats this as valid for [com.example.familysafety.billing.
     * EntitlementCalculator.SUBSCRIPTION_STALENESS_MS], not forever — a subscription that
     * stops being re-confirmed (cancelled, refunded, or the paying device is simply gone)
     * ages out on its own, with no explicit "revoke" message needed.
     */
    val subscriptionConfirmedAtEpochMs: Long? = null,
    /** A developer-issued grant code (`billing/GrantCode.kt`), if this family has one. */
    val grantCode: String? = null,
    val removalProofs: Set<RemovalProof> = emptySet(),
    /** [subscriberMemberId]'s signature over [ProfileGuard.subscriptionMessage] (F25). Not hashed. */
    val subscriptionSignature: String? = null,
    /**
     * Keys made after removals (3.3, see [GroupKeys]); [fileEncryptionKey] stays as epoch 0.
     * Append-only and not hashed, for the same reasons as [fileEncryptionKey].
     */
    val groupKeys: List<GroupKey> = emptyList()
) {
    /**
     * Computes deterministic hash of this group state for hash chain integrity.
     * Members sign this hash to approve group membership changes.
     *
     * Deliberately excludes fileEncryptionKey: the hash covers who is in the group, and
     * folding a secret into it would both change every existing group's hash (breaking
     * the chain across the upgrade) and leak nothing useful in return.
     *
     * The billing fields (subscriberMemberId, subscriptionConfirmedAtEpochMs, grantCode) are
     * excluded for the same reason fileEncryptionKey is: they are facts about the family, not
     * membership decisions. They still travel through the ordinary version-bump-and-broadcast
     * path (see GroupStateManager.confirmSubscription / redeemGrantCode), the same way
     * updateMyDisplayName and updateMyColorHue already update non-hashed fields — only the
     * *hash* ignores them, so re-confirming a subscription daily doesn't change what the
     * roster's identity hash looks like, even though it does bump the version.
     *
     * Tombstones ARE covered — they are a security decision, and anything outside this hash
     * is outside the signature, so a stripped tombstone would silently readmit a removed
     * member. They are appended only when non-empty, so a group that has never removed
     * anyone keeps the hash it had before tombstones existed and stays chain-compatible
     * with peers across the upgrade.
     */
    fun computeStateHash(): String {
        val canonical = buildString {
            append(groupId)
            append("|")
            append(groupName)
            append("|")
            append(createdAtEpochMs)
            append("|")
            append(creatorMemberId)
            append("|")
            append(version)
            append("|")
            members.sortedBy { it.memberId }.forEach { member ->
                append(member.memberId)
                append(",")
                append(member.ed25519PublicKey)
                append(",")
                append(member.x25519PublicKey)
                // Only from the second membership on, so every existing roster hashes as before.
                if (member.joinEpoch > 0) {
                    append(",#")
                    append(member.joinEpoch)
                }
                append(";")
            }
            if (removedMemberIds.isNotEmpty()) {
                append("|removed:")
                removedMemberIds.sorted().forEach { id ->
                    append(id)
                    append(";")
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(canonical.toByteArray(Charsets.UTF_8))
            .toHex()
    }

    fun findMemberById(memberId: String): FamilyMember? =
        members.find { it.memberId == memberId }

    fun containsMember(memberId: String): Boolean =
        members.any { it.memberId == memberId }

    companion object {
        /**
         * Deterministic successor when the creator is removed (by quorum vote — see
         * [GroupTransitionValidator]). Every device must compute the same answer
         * independently with no extra vote, so this is a pure function of already-
         * replicated roster state: the longest-standing remaining member, tie-broken
         * by memberId for full determinism when two members share a timestamp.
         *
         * @param members the roster to choose from (already tombstone-filtered by the caller)
         * @param excludedIds member IDs ineligible to succeed (at minimum, the member being removed)
         */
        fun computeSuccessorCreator(members: Set<FamilyMember>, excludedIds: Set<String>): String? =
            members.filterNot { it.memberId in excludedIds }
                .minWithOrNull(compareBy({ it.addedAtEpochMs }, { it.memberId }))
                ?.memberId
    }
}

/**
 * Events emitted by GroupStateManager for UI/service layer consumption.
 */
sealed class GroupStateEvent {
    /** A new member has been added to the family group. */
    data class MemberAdded(val member: FamilyMember, val addedBy: FamilyMember?) : GroupStateEvent()

    /** A member has been removed from the family group. */
    data class MemberRemoved(val member: FamilyMember, val removedBy: FamilyMember?) : GroupStateEvent()

    /** A member's connection state has changed. */
    data class ConnectionStateChanged(
        val member: FamilyMember,
        val oldState: ConnectionState,
        val newState: ConnectionState
    ) : GroupStateEvent()

    /** A member's presence status has changed. */
    data class PresenceChanged(
        val member: FamilyMember,
        val oldStatus: PresenceStatus,
        val newStatus: PresenceStatus
    ) : GroupStateEvent()

    /** Group definition has been updated (name change, settings, etc.). */
    data class GroupUpdated(val oldDefinition: GroupDefinition, val newDefinition: GroupDefinition) : GroupStateEvent()

    /** Local device has been removed from the group by another member. */
    object RemovedFromGroup : GroupStateEvent()

    /** Received a group state that conflicts with local state (requires resolution). */
    data class ConflictDetected(val localVersion: Long, val remoteVersion: Long) : GroupStateEvent()

    /** The group creator role moved to a different member, voluntarily or by quorum vote. */
    data class CreatorTransferred(val oldCreatorMemberId: String, val newCreatorMemberId: String) : GroupStateEvent()

    /** A vote toward removing [targetMemberId] was received; tally is informational only
     *  until [currentCount] reaches [neededCount], at which point the removal is applied. */
    data class RemovalVoteReceived(
        val targetMemberId: String,
        val currentCount: Int,
        val neededCount: Int
    ) : GroupStateEvent()
}

/**
 * Result type for group operations that may fail.
 */
sealed class GroupOperationResult<out T> {
    data class Success<T>(val value: T) : GroupOperationResult<T>()
    data class Failure(val error: GroupError) : GroupOperationResult<Nothing>()
}

/**
 * Possible errors during group operations.
 */
sealed class GroupError {
    object NotGroupMember : GroupError()
    object NotGroupCreator : GroupError()
    object MemberAlreadyExists : GroupError()
    object MemberNotFound : GroupError()
    object InvalidSignature : GroupError()
    object VersionConflict : GroupError()

    /** The update was validly signed, but the signer was not allowed to make this change. */
    data class UnauthorizedChange(val reason: String) : GroupError()

    /** A quorum-removal vote had valid signatures but not enough of them. */
    data class InsufficientQuorum(val have: Int, val needed: Int) : GroupError()
    object StorageError : GroupError()

    /**
     * A roster that leaves this phone out without a tombstone for its membership. Stale, not a
     * removal; the state is kept and a fresh copy should be requested.
     */
    object AbsentWithoutRemoval : GroupError()
    data class CryptoError(val message: String) : GroupError()
    data class NetworkError(val message: String) : GroupError()
}
