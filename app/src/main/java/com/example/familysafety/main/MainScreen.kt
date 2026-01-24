package com.example.familysafety.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.familysafety.sync.GroupSyncManager
import androidx.compose.ui.unit.dp
sealed class MainRoute(val route: String, val label: String, val icon: ImageVector) {
    data object Map : MainRoute("map", "Map", Icons.Default.Place)
    data object Members : MainRoute("members", "Members", Icons.Default.People)
    data object Settings : MainRoute("settings", "Settings", Icons.Default.Settings)
}

private val bottomNavItems = listOf(
    MainRoute.Map,
    MainRoute.Members,
    MainRoute.Settings
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    syncManager: GroupSyncManager = hiltViewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

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
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
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
                MembersScreen(viewModel = viewModel)
            }
            composable(MainRoute.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
