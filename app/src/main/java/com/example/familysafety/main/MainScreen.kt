package com.example.familysafety.main

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.familysafety.onboarding.BatteryOptimizationScreen
import kotlinx.coroutines.launch

sealed class MainRoute(val route: String, val label: String, val icon: ImageVector) {
    data object Map      : MainRoute("map",       "Map",      Icons.Default.Place)
    data object Members  : MainRoute("members",   "Members",  Icons.Default.People)
    data object Chat     : MainRoute("chat",       "Chat",     Icons.Default.Chat)
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
    PagerTab("Alerts",   Icons.Default.Notifications),
    PagerTab("Settings", Icons.Default.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    filesViewModel: FilesViewModel = hiltViewModel(),
    geofenceViewModel: GeofenceViewModel = hiltViewModel(),
    onThemeChanged: (ThemeMode) -> Unit = {},
    onReplayTutorial: () -> Unit = {},
    navigateTo: String? = null
) {
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
            val totalUnreadCount by chatViewModel.totalUnreadCount.collectAsState()
            val pendingJoinCount by viewModel.pendingJoinRequests.collectAsState()
            val geofences by geofenceViewModel.geofences.collectAsState()

            val pagerState = rememberPagerState(pageCount = { 4 })
            val scope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }
            var showBatteryFixDialog by remember { mutableStateOf(false) }

            // When opened from a notification, map route string to pager page.
            LaunchedEffect(navigateTo) {
                if (!navigateTo.isNullOrBlank()) {
                    if (navigateTo == "battery_fix") {
                        showBatteryFixDialog = true
                        return@LaunchedEffect
                    }
                    val page = when {
                        navigateTo == MainRoute.Map.route                         -> 0
                        navigateTo == MainRoute.Members.route                     -> 1
                        navigateTo.startsWith("chat")                             -> 2
                        navigateTo == MainRoute.Settings.route                    -> 3
                        else                                                      -> -1
                    }
                    if (page >= 0) {
                        pagerState.animateScrollToPage(page)
                    } else {
                        navController.navigate(navigateTo) { launchSingleTop = true }
                    }
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
                                pagerState.animateScrollToPage(1)
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
                        title = { Text("FamilySafety") },
                        actions = {
                            EncryptionChip(modifier = Modifier.padding(end = 4.dp))
                            ConnectionBadge(
                                viewModel = viewModel,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            SyncIndicator(
                                syncManager = syncManager,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        pagerTabs.forEachIndexed { index, tab ->
                            val selected = pagerState.currentPage == index
                            val chatBadge   = index == 2 && totalUnreadCount > 0
                            val familyBadge = index == 1 && pendingJoinCount.isNotEmpty()

                            // Local glow+icon lambda — avoids duplicating the Box tree.
                            val glowIcon: @Composable () -> Unit = {
                                Box(contentAlignment = Alignment.Center) {
                                    if (selected) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(
                                                    brush = Brush.radialGradient(
                                                        colors = listOf(
                                                            TealPrimary.copy(alpha = 0.25f),
                                                            Color.Transparent
                                                        ),
                                                        radius = 60f
                                                    ),
                                                    shape = CircleShape
                                                )
                                        )
                                    }
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.label,
                                        tint = if (selected) TealPrimary else TextDisabled
                                    )
                                }
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
                                label = null,
                                selected = selected,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Color.Transparent
                                )
                            )
                        }
                    }
                }
            ) { paddingValues ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    userScrollEnabled = true,
                    beyondViewportPageCount = 1
                ) { page ->
                    when (page) {
                        0 -> MapScreen(
                            viewModel = viewModel,
                            geofences = geofences,
                            onLongPressMap = { geoPoint ->
                                geofenceViewModel.setPendingGeoPoint(
                                    geoPoint.latitude,
                                    geoPoint.longitude
                                )
                                navController.navigate("geofence_editor/new")
                            },
                            onNavigateToZones = {
                                navController.navigate(MainRoute.Zones.route)
                            }
                        )
                        1 -> MembersScreen(
                            viewModel = viewModel,
                            onNavigateToMap = {
                                scope.launch { pagerState.animateScrollToPage(0) }
                            },
                            onNavigateToInvite = { navController.navigate("invite") },
                            onNavigateToHistory = { memberId ->
                                navController.navigate(HistoryRoute.route(memberId))
                            },
                            onNavigateToFiles = {
                                navController.navigate(MainRoute.Files.route)
                            }
                        )
                        2 -> ChatScreen(
                            memberId = "",
                            onBack = {},
                            viewModel = chatViewModel
                        )
                        3 -> SettingsScreen(
                            viewModel = viewModel,
                            onThemeChanged = onThemeChanged,
                            onNavigateToCrop = { navController.navigate("avatar_crop") },
                            onReplayTutorial = onReplayTutorial,
                            onNavigateToPrivacy = { navController.navigate("privacy") }
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
            FilesScreen(viewModel = filesViewModel)
        }
        composable(MainRoute.Zones.route) {
            GeofenceListScreen(
                onNavigateToEditor = { geofenceId ->
                    navController.navigate("geofence_editor/$geofenceId")
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
                onBack = { navController.popBackStack() }
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
