package com.example.familysafety.group

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
    val colorHue: Float? = null   // 0–359; null = derive from memberId hash
) {
    /** MQTT topic for sending messages to this member: SHA-256(ed25519PubKey) */

    val mqttInboxTopic: String by lazy {
        "inbox/${computeTopicHash(ed25519PublicKey)}"
    }

    companion object {
        /**
         * Computes the unlinkable pseudonym topic from a public key.
         * Uses full SHA-256 hash to prevent correlation attacks.
         */
        fun computeTopicHash(publicKeyHex: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(publicKeyHex.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

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
 */
@Serializable
data class GroupDefinition(
    val groupId: String,
    val groupName: String,
    val createdAtEpochMs: Long,
    val creatorMemberId: String,
    val members: Set<FamilyMember>,
    val version: Long,
    val previousStateHash: String? = null
) {
    /**
     * Computes deterministic hash of this group state for hash chain integrity.
     * Members sign this hash to approve group membership changes.
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
                append(";")
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun findMemberById(memberId: String): FamilyMember? =
        members.find { it.memberId == memberId }

    fun containsMember(memberId: String): Boolean =
        members.any { it.memberId == memberId }
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
    object StorageError : GroupError()
    data class CryptoError(val message: String) : GroupError()
    data class NetworkError(val message: String) : GroupError()
}
