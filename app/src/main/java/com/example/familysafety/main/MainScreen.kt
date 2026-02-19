package com.example.familysafety.main

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
import com.example.familysafety.chat.ChatScreen
import com.example.familysafety.chat.ChatViewModel
import com.example.familysafety.chat.ConversationListScreen
import androidx.compose.ui.unit.dp

sealed class MainRoute(val route: String, val label: String, val icon: ImageVector) {
    data object Map : MainRoute("map", "Map", Icons.Default.Place)
    data object Members : MainRoute("members", "Members", Icons.Default.People)
    data object Chat : MainRoute("chat", "Chat", Icons.Default.Chat)
    data object Settings : MainRoute("settings", "Settings", Icons.Default.Settings)
}

// Chat sub-routes (not in bottom nav)
object ChatRoutes {
    const val CONVERSATION_LIST = "chat/conversations"
    const val CHAT_DETAIL = "chat/conversation/{memberId}"

    fun chatDetail(memberId: String) = "chat/conversation/$memberId"
}

private val bottomNavItems = listOf(
    MainRoute.Map,
    MainRoute.Members,
    MainRoute.Chat,
    MainRoute.Settings
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val syncManager = viewModel.groupSyncManager
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Observe unread chat count for badge
    val totalUnreadCount by chatViewModel.totalUnreadCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("FamilySafety")
                },
                actions = {
                    SyncIndicator(
                        syncManager = syncManager,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { item ->
                    NavigationBarItem(
                        icon = {
                            if (item == MainRoute.Chat && totalUnreadCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge {
                                            Text(
                                                text = if (totalUnreadCount > 99) "99+" else totalUnreadCount.toString()
                                            )
                                        }
                                    }
                                ) {
                                    Icon(item.icon, contentDescription = item.label)
                                }
                            } else {
                                Icon(item.icon, contentDescription = item.label)
                            }
                        },
                        label = { Text(item.label) },
                        selected = currentDestination?.hierarchy?.any {
                            it.route == item.route || it.route?.startsWith("chat/") == true && item == MainRoute.Chat
                        } == true,
                        onClick = {
                            val route = if (item == MainRoute.Chat) {
                                ChatRoutes.CONVERSATION_LIST
                            } else {
                                item.route
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
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(MainRoute.Map.route) {
                MapScreen(viewModel = viewModel)
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
                    }
                )
            }
            composable(MainRoute.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }

            // Chat screens
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
        }
    }
}
