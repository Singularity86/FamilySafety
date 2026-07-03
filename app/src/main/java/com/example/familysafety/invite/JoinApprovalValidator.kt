package com.example.familysafety.invite

import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupTransitionValidator

/**
 * Validates a decrypted join approval against what the joiner learned from the
 * QR invite (groupId + inviterMemberId) — the only trust anchor a not-yet-member
 * device has. Pure JVM logic so it is unit-testable without lazysodium.
 */
object JoinApprovalValidator {

    /**
     * memberId = SHA-256(ed25519PublicKeyBytes).take(16).toHex()
     * Must match CryptoProvider.deriveMemberId exactly.
     */
    fun deriveMemberId(ed25519PublicKeyHex: String): String =
        GroupTransitionValidator.deriveMemberIdFromKey(ed25519PublicKeyHex)

    /**
     * @return null if the approval is trustworthy, otherwise a reason to log.
     */
    fun validate(
        groupDefinition: GroupDefinition,
        expectedGroupId: String,
        expectedInviterMemberId: String,
        inviterEd25519PublicKeyHex: String,
        myMemberId: String
    ): String? {
        val derivedInviterId = try {
            deriveMemberId(inviterEd25519PublicKeyHex)
        } catch (e: Exception) {
            return "inviter key is not valid hex"
        }
        if (derivedInviterId != expectedInviterMemberId) {
            return "inviter key does not hash to the invited-by member ID from the QR invite"
        }
        if (groupDefinition.groupId != expectedGroupId) {
            return "group ID does not match the QR invite"
        }
        val inviter = groupDefinition.findMemberById(expectedInviterMemberId)
            ?: return "inviter is not a member of the delivered group definition"
        if (!inviter.ed25519PublicKey.equals(inviterEd25519PublicKeyHex, ignoreCase = true)) {
            return "inviter key in group definition does not match the signing key"
        }
        if (!groupDefinition.containsMember(myMemberId)) {
            return "delivered group definition does not include this device"
        }
        return null
    }
}
