package com.example.familysafety.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun OnboardingNavigation(
    onOnboardingComplete: () -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val viewModel: OnboardingViewModel = hiltViewModel()

    // Show tutorial slides first unless the user has already seen them.
    val startDestination = if (isTutorialShown(context)) {
        OnboardingRoute.Welcome.route
    } else {
        OnboardingRoute.Tutorial.route
    }

    // Track which onboarding path is active so EnterName can route correctly.
    var isRestorePath by remember { mutableStateOf(false) }
    var isJoinPath by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(OnboardingRoute.Tutorial.route) {
            TutorialScreen(
                onFinish = {
                    navController.navigate(OnboardingRoute.Welcome.route) {
                        popUpTo(OnboardingRoute.Tutorial.route) { inclusive = true }
                    }
                }
            )
        }

        composable(OnboardingRoute.Welcome.route) {
            WelcomeScreen(
                onCreateNew = {
                    isRestorePath = false
                    isJoinPath = false
                    navController.navigate(OnboardingRoute.GenerateMnemonic.route)
                },
                onRestore = {
                    isRestorePath = true
                    isJoinPath = false
                    navController.navigate(OnboardingRoute.RestoreMnemonic.route)
                },
                onJoinExisting = {
                    // Joining requires keys — route through mnemonic generation first.
                    isJoinPath = true
                    navController.navigate(OnboardingRoute.GenerateMnemonic.route)
                },
                onReplayTutorial = {
                    navController.navigate(OnboardingRoute.Tutorial.route)
                }
            )
        }

        composable(OnboardingRoute.GenerateMnemonic.route) {
            GenerateMnemonicScreen(
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
                    // A restored phone holds no family and cannot find one on its own, so it
                    // rejoins through a member's invite like anyone else (RejoinPolicy).
                    when {
                        isJoinPath || isRestorePath -> navController.navigate(OnboardingRoute.JoinFamily.route)
                        else -> navController.navigate(OnboardingRoute.CreateFamily.route)
                    }
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
                isRestore = isRestorePath,
                onComplete = onOnboardingComplete,
                onBack = { navController.popBackStack() },
                onNavigateToScanner = { navController.navigate("qr_scanner") },
                navController = navController
            )
        }

        composable("qr_scanner") {
            QrScannerScreen(
                onCodeScanned = { code ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("scanned_code", code)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
