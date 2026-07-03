package com.example.familysafety.group

sealed class MembershipState {
    data object Unauthenticated : MembershipState()
    data class PendingApproval(
        val invitedByName: String,
        val familyName: String
    ) : MembershipState()
    /** Approval received — show confirmation screen before restarting. */
    data class ApprovalReceived(val familyName: String) : MembershipState()
    /** The inviter declined the join request — show a notice before returning to onboarding. */
    data class Rejected(val familyName: String) : MembershipState()
    data object Approved : MembershipState()
}
