package com.example.familysafety.onboarding

sealed class OnboardingRoute(val route: String) {
    data object Tutorial : OnboardingRoute("tutorial")
    data object Welcome : OnboardingRoute("welcome")
    data object GenerateMnemonic : OnboardingRoute("generate_mnemonic")
    data object ConfirmMnemonic : OnboardingRoute("confirm_mnemonic")
    data object RestoreMnemonic : OnboardingRoute("restore_mnemonic")
    data object RestoreComplete : OnboardingRoute("restore_complete")
    data object EnterName : OnboardingRoute("enter_name")
    data object CreateFamily : OnboardingRoute("create_family")
    data object JoinFamily : OnboardingRoute("join_family")
}
