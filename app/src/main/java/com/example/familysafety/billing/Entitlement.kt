package com.example.familysafety.billing

/** Whether a family may use the paid-gated features right now, and why. */
sealed class Entitlement {
    /** The family existed before the paywall shipped. Free forever, no wire cost. */
    object Grandfathered : Entitlement()

    /** Developer-issued grant code, verified locally against this family's groupId. */
    data class Granted(val expiresAtEpochMs: Long?) : Entitlement()

    /** Inside the free trial window that started when the family was created. */
    data class Trial(val daysLeft: Int) : Entitlement()

    /** A member's subscription was confirmed against Play recently enough to trust. */
    object Subscribed : Entitlement()

    /** None of the above. Gated features are hidden. */
    object Expired : Entitlement()

    val isEntitled: Boolean
        get() = this !is Expired
}
