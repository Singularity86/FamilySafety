package com.example.familysafety.ui.onboarding

import androidx.compose.runtime.Composable
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

    NavHost(
        navController = navController,
        startDestination = OnboardingRoute.Welcome.route
    ) {
        composable(OnboardingRoute.Welcome.route) {
            WelcomeScreen(
                onCreateNew = {
                    navController.navigate(OnboardingRoute.GenerateMnemonic.route)
                },
                onRestore = {
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
                    navController.navigate(OnboardingRoute.CreateFamily.route)
                },
                onBack = {
                    navController.popBackStack()
                }
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
