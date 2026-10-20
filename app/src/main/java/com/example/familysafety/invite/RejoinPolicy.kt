package com.example.familysafety.invite

import com.example.familysafety.core.hexToBytes
import com.example.familysafety.core.toHex
import com.example.familysafety.group.GroupDefinition

/**
 * What the inviter does with an incoming [JoinRequest]. Pure JVM so it is unit-testable;
 * signature checking is passed in.
 *
 * A recovery phrase regenerates a member's keys and therefore their member ID, but a restored
 * phone holds no family: it does not know the group, its members or their keys, so it cannot
 * find its way back alone. It rejoins the way anyone joins, by scanning a member's QR invite and
 * sending a join request under its restored identity. This decides whether that request needs
 * a person to approve it.
 */
object RejoinPolicy {

    sealed class Verdict {
        /** Still a member, same keys, and the request is signed by them: send the family back. */
        data object AutoApprove : Verdict()

        /** A newcomer, a returning ex-member, or an unsigned request: ask the inviter. */
        data object AskInviter : Verdict()

        /** Never approve, not even by hand. [reason] is for the log. */
        data class Refuse(val reason: String) : Verdict()
    }

    fun evaluate(
        request: JoinRequest,
        group: GroupDefinition,
        verifySignature: (message: ByteArray, signature: ByteArray, ed25519PublicKeyHex: String) -> Boolean
    ): Verdict {
        val edHex = request.ed25519PublicKey.toHex()
        val derivedId = try {
            JoinApprovalValidator.deriveMemberId(edHex)
        } catch (e: Exception) {
            return Verdict.Refuse("signing key is not valid")
        }
        if (derivedId != request.requesterId) {
            return Verdict.Refuse("member ID does not match the signing key")
        }

        keyConflict(request, group)?.let { return Verdict.Refuse(it) }
        if (!group.containsMember(request.requesterId)) return Verdict.AskInviter

        val signature = request.signature ?: return Verdict.AskInviter
        val valid = try {
            verifySignature(request.signedBytes(), signature.hexToBytes(), edHex)
        } catch (e: Exception) {
            false
        }
        return if (valid) Verdict.AutoApprove else Verdict.Refuse("signature does not verify")
    }

    /**
     * Non-null when the request names an existing member but carries keys other than the ones
     * on file. The approval is encrypted to the request's X25519 key, so approving such a
     * request — even by hand, on a prompt that looks like a known family member — would hand
     * the whole family definition, file key included, to whoever made the request.
     */
    fun keyConflict(request: JoinRequest, group: GroupDefinition): String? {
        val existing = group.findMemberById(request.requesterId) ?: return null
        if (!existing.ed25519PublicKey.equals(request.ed25519PublicKey.toHex(), ignoreCase = true)) {
            return "signing key differs from the one on file for this member"
        }
        if (!existing.x25519PublicKey.equals(request.x25519PublicKey.toHex(), ignoreCase = true)) {
            return "encryption key differs from the one on file for this member"
        }
        return null
    }


}
