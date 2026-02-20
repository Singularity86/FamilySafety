package com.example.familysafety.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun OnboardingNavigation(
    onOnboardingComplete: () -> Unit
) {
    val navController = rememberNavController()
    val viewModel: OnboardingViewModel = hiltViewModel()

    // Track whether the user is on the restore path so EnterName can route correctly.
    var isRestorePath by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = OnboardingRoute.Welcome.route
    ) {
        composable(OnboardingRoute.Welcome.route) {
            WelcomeScreen(
                onCreateNew = {
                    isRestorePath = false
                    navController.navigate(OnboardingRoute.GenerateMnemonic.route)
                },
                onRestore = {
                    isRestorePath = true
                    navController.navigate(OnboardingRoute.RestoreMnemonic.route)
                },
                onJoinExisting = {
                    navController.navigate(OnboardingRoute.JoinFamily.route)
                }
            )
        }

        composable(OnboardingRoute.GenerateMnemonic.route) {
            GenerateMnemonicScreen(
                viewModel = viewModel,
                onNext = {
                    navController.navigate(OnboardingRoute.ConfirmMnemonic.route)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OnboardingRoute.ConfirmMnemonic.route) {
            ConfirmMnemonicScreen(
                viewModel = viewModel,
                onNext = {
                    navController.navigate(OnboardingRoute.EnterName.route)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OnboardingRoute.RestoreMnemonic.route) {
            RestoreMnemonicScreen(
                viewModel = viewModel,
                onNext = {
                    navController.navigate(OnboardingRoute.EnterName.route)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OnboardingRoute.EnterName.route) {
            EnterNameScreen(
                viewModel = viewModel,
                onNext = {
                    // Restore path skips CreateFamily — keys are already derived
                    // from the mnemonic, no new group should be created.
                    if (isRestorePath) {
                        navController.navigate(OnboardingRoute.RestoreComplete.route)
                    } else {
                        navController.navigate(OnboardingRoute.CreateFamily.route)
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OnboardingRoute.RestoreComplete.route) {
            RestoreCompleteScreen(
                viewModel = viewModel,
                onComplete = onOnboardingComplete
            )
        }

        composable(OnboardingRoute.CreateFamily.route) {
            CreateFamilyScreen(
                viewModel = viewModel,
                onComplete = onOnboardingComplete,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OnboardingRoute.JoinFamily.route) {
            JoinFamilyScreen(
                viewModel = viewModel,
                onComplete = onOnboardingComplete,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
