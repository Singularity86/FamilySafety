package com.example.familysafety.main

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.familysafety.chat.ChatScreen
import com.example.familysafety.chat.ChatViewModel
import com.example.familysafety.chat.ConversationListScreen
import com.example.familysafety.files.FilesViewModel
import com.example.familysafety.geofence.GeofenceEditorScreen
import com.example.familysafety.geofence.GeofenceListScreen
import com.example.familysafety.geofence.GeofenceViewModel
import com.example.familysafety.ui.theme.ThemeMode
import com.example.familysafety.ui.screens.PrivacyScreen
import androidx.compose.ui.unit.dp

sealed class MainRoute(val route: String, val label: String, val icon: ImageVector) {
    data object Map : MainRoute("map", "Map", Icons.Default.Place)
    data object Members : MainRoute("members", "Members", Icons.Default.People)
    data object Chat : MainRoute("chat", "Chat", Icons.Default.Chat)
    data object Files : MainRoute("files", "Files", Icons.Default.Folder)
    data object Zones : MainRoute("geofences", "Zones", Icons.Default.LocationCity)
    data object Settings : MainRoute("settings", "Settings", Icons.Default.Settings)
}

// History route (not in bottom nav — detail screen)
object HistoryRoute {
    const val PATTERN = "history/{memberId}"
    fun route(memberId: String) = "history/$memberId"
}

// Chat sub-routes (not in bottom nav)
object ChatRoutes {
    const val GROUP_CHAT = "chat/group"
    const val CONVERSATION_LIST = "chat/conversations"
    const val CHAT_DETAIL = "chat/conversation/{memberId}"

    fun chatDetail(memberId: String) = "chat/conversation/$memberId"
}

// Routes that should hide the bottom nav bar
private val fullScreenRoutes = setOf(
    "invite", "qr_scanner", HistoryRoute.PATTERN, "avatar_crop",
    "geofence_editor/{geofenceId}"
)

private val bottomNavItems = listOf(
    MainRoute.Map,
    MainRoute.Members,
    MainRoute.Chat,
    MainRoute.Files,
    MainRoute.Zones,
    MainRoute.Settings
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
    val syncManager = viewModel.groupSyncManager
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Observe unread chat count and pending join requests for badges
    val totalUnreadCount by chatViewModel.totalUnreadCount.collectAsState()
    val pendingJoinCount by viewModel.pendingJoinRequests.collectAsState()

    val isFullScreen = currentDestination?.route in fullScreenRoutes

    val snackbarHostState = remember { SnackbarHostState() }

    // Navigate to a specific tab when opened from a notification tap.
    LaunchedEffect(navigateTo) {
        if (!navigateTo.isNullOrBlank()) {
            navController.navigate(navigateTo) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Show an in-app snackbar when a join request arrives while the app is in the foreground.
    // Use LaunchedEffect(Unit) + collect so the coroutine lives for the full lifetime of
    // MainScreen and is never cancelled mid-showSnackbar by a list change (which was the
    // bug with LaunchedEffect(pendingJoinCount) — any re-emit while the snackbar was
    // waiting for user input would cancel the coroutine, so tapping "View" dismissed
    // the snackbar but triggered no navigation).
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
                        navController.navigate(MainRoute.Members.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            }
            prevCount = newCount
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isFullScreen) TopAppBar(
                title = {
                    Text("FamilySafety")
                },
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
            if (isFullScreen) return@Scaffold
            NavigationBar {
                bottomNavItems.forEach { item ->
                    NavigationBarItem(
                        icon = {
                            val chatBadge = item == MainRoute.Chat && totalUnreadCount > 0
                            val membersBadge = item == MainRoute.Members && pendingJoinCount.isNotEmpty()
                            if (chatBadge || membersBadge) {
                                BadgedBox(
                                    badge = {
                                        if (chatBadge) {
                                            Badge {
                                                Text(
                                                    text = if (totalUnreadCount > 99) "99+" else totalUnreadCount.toString()
                                                )
                                            }
                                        } else {
                                            Badge()
                                        }
                                    }
                                ) {
                                    Icon(item.icon, contentDescription = item.label)
                                }
                            } else {
                                Icon(item.icon, contentDescription = item.label)
                            }
                        },
                        label = null,
                        selected = currentDestination?.hierarchy?.any {
                            it.route == item.route || it.route?.startsWith("chat/") == true && item == MainRoute.Chat
                        } == true,
                        onClick = {
                            val route = when (item) {
                                MainRoute.Chat -> ChatRoutes.GROUP_CHAT
                                else -> item.route
                            }
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = MainRoute.Map.route,
            modifier = Modifier.padding(paddingValues),
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
            composable(MainRoute.Map.route) {
                val geofences by geofenceViewModel.geofences.collectAsState()
                MapScreen(
                    viewModel = viewModel,
                    geofences = geofences,
                    onLongPressMap = { geoPoint ->
                        geofenceViewModel.setPendingGeoPoint(geoPoint.latitude, geoPoint.longitude)
                        navController.navigate("geofence_editor/new")
                    }
                )
            }
            composable(MainRoute.Members.route) {
                MembersScreen(
                    viewModel = viewModel,
                    onNavigateToMap = {
                        navController.navigate(MainRoute.Map.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToInvite = {
                        navController.navigate("invite")
                    },
                    onNavigateToHistory = { memberId ->
                        navController.navigate(HistoryRoute.route(memberId))
                    }
                )
            }

            composable("invite") {
                InviteScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
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
            composable(MainRoute.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onThemeChanged = onThemeChanged,
                    onNavigateToCrop = { navController.navigate("avatar_crop") },
                    onReplayTutorial = onReplayTutorial,
                    onNavigateToPrivacy = { navController.navigate("privacy") }
                )
            }

            composable("avatar_crop") {
                AvatarCropScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // Chat screens
            composable(ChatRoutes.GROUP_CHAT) {
                ChatScreen(
                    memberId = "",   // empty = open group chat
                    onBack = { navController.popBackStack() },
                    viewModel = chatViewModel
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
                arguments = listOf(
                    navArgument("memberId") { type = NavType.StringType }
                )
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

            // History detail screen
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
}
