package com.example.familysafety

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.group.MembershipState
import com.example.familysafety.location.LocationService
import com.example.familysafety.location.LocationPermissionHelper
import com.example.familysafety.onboarding.MembershipViewModel
import com.example.familysafety.onboarding.OnboardingNavigation
import com.example.familysafety.onboarding.PermissionOnboardingFlow
import com.example.familysafety.onboarding.TutorialScreen
import com.example.familysafety.onboarding.isPermissionsFlowShown
import com.example.familysafety.main.MainScreen
import com.example.familysafety.main.TipWindow
import com.example.familysafety.main.disableTips
import com.example.familysafety.main.incrementSessionCount
import com.example.familysafety.main.shouldShowTip
import com.example.familysafety.ui.screens.ApprovedScreen
import com.example.familysafety.ui.screens.PendingMemberScreen
import com.example.familysafety.ui.theme.AppTheme
import com.example.familysafety.ui.theme.ThemeMode
import com.example.familysafety.ui.theme.ThemePreference
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main entry point for the application
 * Checks if keys are initialized and shows onboarding or main screen accordingly
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appInitializer: AppInitializer

    @Inject
    lateinit var localMemberId: LocalMemberId

    // Tracks the tab the app should navigate to (set from notification tap).
    private val navigateTo = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let Compose handle insets (keyboard, status bar) instead of the OS resizing the window.
        // Without this, imePadding() fights the window resize and causes a blank-screen flash.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        navigateTo.value = intent?.getStringExtra("navigate_to")

        setContent {
            val systemDark = isSystemInDarkTheme()
            var themeMode by remember { mutableStateOf(ThemePreference.get(this@MainActivity)) }
            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }

            AppTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val membershipViewModel: MembershipViewModel = hiltViewModel()
                    val membershipState by membershipViewModel.membershipState.collectAsState()

                    // Trigger a full process restart when background approval arrives while the
                    // app is open (MembershipViewModel emits once via approvedEvent).
                    LaunchedEffect(Unit) {
                        membershipViewModel.approvedEvent.collect {
                            val restartIntent = packageManager
                                .getLaunchIntentForPackage(packageName)!!
                                .apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    )
                                }
                            startActivity(restartIntent)
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    }

                    when (membershipState) {
                        is MembershipState.Approved -> {
                            LaunchedEffect(Unit) {
                                incrementSessionCount(this@MainActivity)
                                appInitializer.initialize()
                            }

                            var showPermissionFlow by remember {
                                mutableStateOf(!isPermissionsFlowShown(this@MainActivity))
                            }

                            LaunchedEffect(showPermissionFlow) {
                                if (!showPermissionFlow) {
                                    startLocationService()
                                }
                            }

                            var showTip by remember { mutableStateOf(shouldShowTip(this@MainActivity)) }
                            var showTutorialOverlay by remember { mutableStateOf(false) }
                            var navigateToSettings by remember { mutableStateOf(false) }

                            MainScreen(
                                navigateTo = if (navigateToSettings) {
                                    navigateToSettings = false
                                    "settings"
                                } else navigateTo.value,
                                onThemeChanged = { mode ->
                                    ThemePreference.set(this@MainActivity, mode)
                                    themeMode = mode
                                },
                                onReplayTutorial = { showTutorialOverlay = true }
                            )

                            if (showPermissionFlow) {
                                PermissionOnboardingFlow(
                                    onComplete = { showPermissionFlow = false }
                                )
                            }

                            // Tips only appear when no other overlay is active.
                            if (showTip && !showPermissionFlow) {
                                TipWindow(
                                    onDismiss = { showTip = false },
                                    onDisableTips = {
                                        disableTips(this@MainActivity)
                                        showTip = false
                                    },
                                    onOpenSettings = { navigateToSettings = true }
                                )
                            }

                            if (showTutorialOverlay) {
                                TutorialScreen(
                                    onFinish = { showTutorialOverlay = false }
                                )
                            }
                        }
                        is MembershipState.ApprovalReceived -> {
                            ApprovedScreen(
                                familyName = (membershipState as MembershipState.ApprovalReceived).familyName,
                                onContinue = { membershipViewModel.confirmRestart() }
                            )
                        }
                        is MembershipState.PendingApproval -> {
                            PendingMemberScreen(
                                state = membershipState as MembershipState.PendingApproval,
                                onCancelRequest = { membershipViewModel.cancelPending() }
                            )
                        }
                        is MembershipState.Unauthenticated -> {
                            OnboardingNavigation(
                                onOnboardingComplete = {
                                    // Force a full process restart so the Hilt SingletonComponent
                                    // is rebuilt from scratch with the now-initialized keys.
                                    // finish()+startActivity() is not enough — it reuses the
                                    // same process and the same stale singletons (LocalMemberId="",
                                    // GroupStateManager with empty member ID).
                                    val restartIntent = packageManager
                                        .getLaunchIntentForPackage(packageName)!!
                                        .apply {
                                            addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                                            )
                                        }
                                    startActivity(restartIntent)
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigateTo.value = intent.getStringExtra("navigate_to")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity teardown must not stop location sharing. The foreground service,
        // transports, and watchdogs are intentionally app/session scoped so they
        // survive normal UI closure, task removal, and configuration recreation.
    }

    private fun startLocationService() {
        if (!LocationPermissionHelper.hasAlwaysOnLocationPrerequisites(this)) return
        LocationService.startTracking(this, localMemberId.value)
    }
}
