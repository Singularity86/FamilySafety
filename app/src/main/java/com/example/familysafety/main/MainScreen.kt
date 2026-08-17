package com.example.familysafety.main

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.familysafety.chat.ChatScreen
import com.example.familysafety.chat.ChatViewModel
import com.example.familysafety.chat.ConversationListScreen
import com.example.familysafety.files.FilesViewModel
import com.example.familysafety.geofence.GeofenceEditorScreen
import com.example.familysafety.geofence.GeofenceListScreen
import com.example.familysafety.geofence.GeofenceViewModel
import com.example.familysafety.ui.screens.PrivacyScreen
import com.example.familysafety.ui.theme.TealPrimary
import com.example.familysafety.ui.theme.TextDisabled
import com.example.familysafety.ui.theme.ThemeMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.familysafety.BuildConfig
import com.example.familysafety.onboarding.BatteryOptimizationScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

sealed class MainRoute(val route: String, val label: String, val icon: ImageVector) {
    data object Map      : MainRoute("map",       "Map",      Icons.Default.Place)
    data object Members  : MainRoute("members",   "Members",  Icons.Default.People)
    data object Chat     : MainRoute("chat",       "Chat",     Icons.AutoMirrored.Filled.Chat)
    data object Files    : MainRoute("files",      "Files",    Icons.Default.Folder)
    data object Zones    : MainRoute("geofences",  "Zones",    Icons.Default.LocationCity)
    data object Settings : MainRoute("settings",   "Settings", Icons.Default.Settings)
}

// History route (not in bottom nav — detail screen)
object HistoryRoute {
    const val PATTERN = "history/{memberId}"
    fun route(memberId: String) = "history/$memberId"
}

// Chat sub-routes (not in bottom nav)
object ChatRoutes {
    const val GROUP_CHAT        = "chat/group"
    const val CONVERSATION_LIST = "chat/conversations"
    const val CHAT_DETAIL       = "chat/conversation/{memberId}"

    fun chatDetail(memberId: String) = "chat/conversation/$memberId"
}

// 4 pager pages — label/icon for bottom nav
private data class PagerTab(val label: String, val icon: ImageVector)
private val pagerTabs = listOf(
    PagerTab("Home",     Icons.Default.Place),
    PagerTab("Family",   Icons.Default.People),
    PagerTab("Files",    Icons.Default.Folder),
    PagerTab("Chat",     Icons.AutoMirrored.Filled.Chat),
    PagerTab("Settings", Icons.Default.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    filesViewModel: FilesViewModel = hiltViewModel(),
    // Hoisted so the Files screen and the vault share one instance: the session, and with
    // it the key, is held in the ViewModel and must not be recreated between the two.
    vaultViewModel: com.example.familysafety.vault.VaultViewModel = hiltViewModel(),
    geofenceViewModel: GeofenceViewModel = hiltViewModel(),
    onThemeChanged: (ThemeMode) -> Unit = {},
    onReplayTutorial: () -> Unit = {},
    navigateTo: String? = null,
    onNavigationHandled: () -> Unit = {}
) {
    val driveEstimateState by viewModel.driveEstimateState.collectAsState()
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = MainRoute.Map.route,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(220)
            )
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(220)
            )
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(220)
            )
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(220)
            )
        }
    ) {
        // -----------------------------------------------------------------------
        // TOP-LEVEL PAGER HOME  (start destination)
        // Scaffold lives here so detail screens slide over the entire chrome.
        // -----------------------------------------------------------------------
        composable(MainRoute.Map.route) {
            val syncManager = viewModel.groupSyncManager
            val groupName by viewModel.groupName.collectAsState()
            val totalUnreadCount by chatViewModel.totalUnreadCount.collectAsState()
            val pendingJoinCount by viewModel.pendingJoinRequests.collectAsState()
            val geofences by geofenceViewModel.geofences.collectAsState()

            val pagerState = rememberPagerState(pageCount = { 5 })
            val scope = rememberCoroutineScope()
            var pagerNavigationJob by remember { mutableStateOf<Job?>(null) }
            val snackbarHostState = remember { SnackbarHostState() }
            var showBatteryFixDialog by remember { mutableStateOf(false) }

            fun navigatePagerTo(page: Int, animated: Boolean = false) {
                if (page == pagerState.currentPage && !pagerState.isScrollInProgress) return
                if (BuildConfig.DEBUG) {
                    Timber.d(
                        "Tab navigation requested: ${pagerState.currentPage} -> $page animated=$animated"
                    )
                }
                pagerNavigationJob?.cancel()
                pagerNavigationJob = scope.launch {
                    if (animated) {
                        pagerState.animateScrollToPage(page)
                    } else {
                        pagerState.scrollToPage(page)
                    }
                    if (BuildConfig.DEBUG) {
                        Timber.d("Tab navigation settled on page ${pagerState.currentPage}")
                    }
                }
            }

            DriveEstimateDialog(
                state = driveEstimateState,
                onDismiss = viewModel::dismissDriveEstimate,
                onShowOnMap = { memberId ->
                    viewModel.dismissDriveEstimate()
                    viewModel.focusOnMember(memberId)
                    navigatePagerTo(0)
                }
            )

            // When opened from a notification, map route string to pager page.
            // The target is cleared once handled so tapping a second notification
            // with the same destination re-triggers this effect instead of being
            // swallowed as an unchanged key.
            LaunchedEffect(navigateTo) {
                if (!navigateTo.isNullOrBlank()) {
                    if (navigateTo == "battery_fix") {
                        showBatteryFixDialog = true
                        onNavigationHandled()
                        return@LaunchedEffect
                    }
                    val page = when {
                        navigateTo == MainRoute.Map.route                         -> 0
                        navigateTo == MainRoute.Members.route                     -> 1
                        navigateTo == MainRoute.Files.route                       -> 2
                        navigateTo.startsWith("chat")                             -> 3
                        navigateTo == MainRoute.Settings.route                    -> 4
                        else                                                      -> -1
                    }
                    if (page >= 0) {
                        navigatePagerTo(page)
                    } else {
                        navController.navigate(navigateTo) { launchSingleTop = true }
                    }
                    onNavigationHandled()
                }
            }

            // Show snackbar when a join request arrives while the app is in the foreground.
            LaunchedEffect(Unit) {
                var prevCount = 0
                viewModel.pendingJoinRequests.collect { requests ->
                    val newCount = requests.size
                    if (newCount > prevCount) {
                        val newest = requests.lastOrNull()
                        if (newest != null) {
                            val result = snackbarHostState.showSnackbar(
                                message = "${newest.displayName} wants to join your family",
                                actionLabel = "View",
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                navigatePagerTo(1)
                            }
                        }
                    }
                    prevCount = newCount
                }
            }

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        title = {
                            Column {
                                Text("Jibaro:\nFamily Safety", style = MaterialTheme.typography.titleMedium)
                                if (groupName.isNotBlank()) {
                                    Text(
                                        text = groupName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextDisabled
                                    )
                                }
                            }
                        },
                        actions = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EncryptionChip()
                                ConnectionBadge(viewModel = viewModel)
                                SyncIndicator(syncManager = syncManager)
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 0.dp
                    ) {
                        pagerTabs.forEachIndexed { index, tab ->
                            val selected = pagerState.currentPage == index
                            val chatBadge   = index == 3 && totalUnreadCount > 0
                            val familyBadge = index == 1 && pendingJoinCount.isNotEmpty()

                            // Local glow+icon lambda — avoids duplicating the Box tree.
                            val glowIcon: @Composable () -> Unit = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (selected) TealPrimary else TextDisabled
                                )
                            }

                            NavigationBarItem(
                                icon = {
                                    if (chatBadge || familyBadge) {
                                        BadgedBox(
                                            badge = {
                                                if (chatBadge) {
                                                    Badge {
                                                        Text(
                                                            text = if (totalUnreadCount > 99) "99+"
                                                                   else totalUnreadCount.toString()
                                                        )
                                                    }
                                                } else {
                                                    Badge()
                                                }
                                            }
                                        ) { glowIcon() }
                                    } else {
                                        glowIcon()
                                    }
                                },
                                label = { Text(tab.label) },
                                selected = selected,
                                onClick = { navigatePagerTo(index) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = TealPrimary,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = TextDisabled,
                                    unselectedTextColor = TextDisabled,
                                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }
                }
            ) { paddingValues ->
                val density = LocalDensity.current
                val layoutDirection = LocalLayoutDirection.current
                val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
                val pagerPadding = PaddingValues(
                    start = paddingValues.calculateStartPadding(layoutDirection),
                    top = paddingValues.calculateTopPadding(),
                    end = paddingValues.calculateEndPadding(layoutDirection),
                    bottom = if (pagerState.currentPage == 3 && keyboardVisible) {
                        0.dp
                    } else {
                        paddingValues.calculateBottomPadding()
                    }
                )

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(pagerPadding),
                    userScrollEnabled = pagerState.currentPage != 0,
                    beyondViewportPageCount = 1
                ) { page ->
                    when (page) {
                        0 -> MapScreen(
                            viewModel = viewModel,
                            geofences = geofences,
                            onNavigateToZones = {
                                navController.navigate(MainRoute.Zones.route)
                            },
                            onEdgeSwipeNext = {
                                navigatePagerTo(1, animated = true)
                            }
                        )
                        1 -> MembersScreen(
                            viewModel = viewModel,
                            onNavigateToMap = {
                                navigatePagerTo(0)
                            },
                            onNavigateToInvite = { navController.navigate("invite") },
                            onNavigateToHistory = { memberId ->
                                navController.navigate(HistoryRoute.route(memberId))
                            },
                            onNavigateToFiles = {
                                navigatePagerTo(2)
                            }
                        )
                        2 -> FilesScreen(
                            onOpenStatusBoard = { navController.navigate("file_status_board") },
                            onOpenVault = { code ->
                                vaultViewModel.openWithCode(code)
                                navController.navigate("vault")
                            },
                            viewModel = filesViewModel
                        )
                        3 -> ChatScreen(
                            memberId = "",
                            onBack = {},
                            viewModel = chatViewModel,
                            showTopBar = false
                        )
                        4 -> SettingsScreen(
                            viewModel = viewModel,
                            onThemeChanged = onThemeChanged,
                            onNavigateToCrop = { navController.navigate("avatar_crop") },
                            onReplayTutorial = onReplayTutorial,
                            onNavigateToPrivacy = { navController.navigate("privacy") },
                            onNavigateToSecurity = { navController.navigate("security") }
                        )
                        else -> {}
                    }
                }
            }

            if (showBatteryFixDialog) {
                Dialog(onDismissRequest = { showBatteryFixDialog = false }) {
                    BatteryOptimizationScreen(
                        onAllowed = { showBatteryFixDialog = false },
                        onSkip    = { showBatteryFixDialog = false }
                    )
                }
            }
        }

        // -----------------------------------------------------------------------
        // DETAIL SCREENS  — slide on top of pager home via NavHost transition
        // -----------------------------------------------------------------------
        composable("invite") {
            InviteScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // Files + Zones remain as NavHost destinations even though they are
        // not in the bottom nav, so programmatic navigation still resolves them.
        composable(MainRoute.Files.route) {
            FilesScreen(
                onOpenStatusBoard = { navController.navigate("file_status_board") },
                onOpenVault = { code ->
                    vaultViewModel.openWithCode(code)
                    navController.navigate("vault")
                },
                viewModel = filesViewModel
            )
        }

        // Reached only by submitting text from the Files search box. Not in the bottom nav,
        // not linked from anywhere, and named nothing in the UI — the route exists, but
        // nothing on screen ever points at it.
        composable("vault") {
            VaultScreen(
                onBack = { navController.popBackStack() },
                viewModel = vaultViewModel
            )
        }

        // Per-file state, how many devices hold each file, and the transfer log. Reached
        // from the Files screen rather than the bottom nav — it is a diagnostic, not a
        // destination.
        composable("file_status_board") {
            FileStatusBoardScreen(
                onBack = { navController.popBackStack() },
                viewModel = filesViewModel
            )
        }
        composable(MainRoute.Zones.route) {
            GeofenceListScreen(
                onNavigateToEditor = { geofenceId ->
                    navController.navigate("geofence_editor/$geofenceId")
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "geofence_editor/{geofenceId}",
            arguments = listOf(navArgument("geofenceId") { type = NavType.StringType })
        ) { backStackEntry ->
            val geofenceId = backStackEntry.arguments?.getString("geofenceId") ?: "new"
            GeofenceEditorScreen(
                geofenceId = geofenceId,
                viewModel = geofenceViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToZones = {
                    navController.popBackStack(MainRoute.Zones.route, inclusive = false)
                }
            )
        }

        composable("avatar_crop") {
            AvatarCropScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(ChatRoutes.CONVERSATION_LIST) {
            ConversationListScreen(
                onConversationClick = { memberId ->
                    navController.navigate(ChatRoutes.chatDetail(memberId))
                },
                viewModel = chatViewModel
            )
        }

        composable(
            route = ChatRoutes.CHAT_DETAIL,
            arguments = listOf(navArgument("memberId") { type = NavType.StringType })
        ) { backStackEntry ->
            val memberId = backStackEntry.arguments?.getString("memberId") ?: return@composable
            ChatScreen(
                memberId = memberId,
                onBack = { navController.popBackStack() },
                viewModel = chatViewModel
            )
        }

        composable("security") {
            SecurityScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "privacy",
            enterTransition = {
                scaleIn(initialScale = 0.92f, animationSpec = tween(200)) +
                fadeIn(tween(200))
            },
            exitTransition = {
                scaleOut(targetScale = 0.92f, animationSpec = tween(200)) +
                fadeOut(tween(200))
            }
        ) {
            PrivacyScreen(navController = navController)
        }

        composable(
            route = HistoryRoute.PATTERN,
            arguments = listOf(navArgument("memberId") { type = NavType.StringType })
        ) {
            HistoryScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() }
            )
        }
    }
}
