package com.espotg.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.espotg.app.ui.connect.ConnectScreen
import com.espotg.app.ui.flash.FlashScreen
import com.espotg.app.ui.monitor.MonitorScreen
import com.espotg.app.ui.profiles.ProfilesScreen
import com.espotg.app.ui.releases.ReleasesScreen
import kotlinx.coroutines.launch

object Routes {
    const val CONNECT = "connect"
    const val FLASH = "flash"
    const val MONITOR = "monitor"
    const val PROFILES = "profiles"
    const val RELEASES = "releases"
}

private data class DrawerDestination(val route: String, val label: String, val icon: ImageVector)

private val DRAWER_ITEMS = listOf(
    DrawerDestination(Routes.CONNECT, "Devices", Icons.Filled.Search),
    DrawerDestination(Routes.FLASH, "Flash", Icons.AutoMirrored.Filled.Send),
    DrawerDestination(Routes.MONITOR, "Serial monitor", Icons.AutoMirrored.Filled.List),
    DrawerDestination(Routes.RELEASES, "Linked repo", Icons.Filled.Refresh),
    DrawerDestination(Routes.PROFILES, "Saved profiles", Icons.Filled.Info),
)

@Composable
fun EspOtgNavHost(appViewModel: AppViewModel, navController: NavHostController = rememberNavController()) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val connectionStatus by appViewModel.connectionStatus.collectAsStateWithLifecycle()
    val isConnected = connectionStatus is ConnectionStatus.Identified

    // Context-aware "back": to the Flash screen when a device is connected,
    // otherwise to the main device-selection screen.
    fun backTarget(): String = if (isConnected) Routes.FLASH else Routes.CONNECT

    fun navigateTop(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                Text("EspOtg", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 28.dp))
                Spacer(Modifier.height(8.dp))
                DRAWER_ITEMS.forEach { item ->
                    NavigationDrawerItem(
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        selected = currentRoute == item.route,
                        onClick = {
                            closeDrawer()
                            if (currentRoute != item.route) navigateTop(item.route)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        NavHost(navController = navController, startDestination = Routes.CONNECT) {
            composable(Routes.CONNECT) {
                ConnectScreen(
                    appViewModel = appViewModel,
                    onConnected = { navigateTop(Routes.FLASH) },
                    onOpenDrawer = openDrawer,
                )
            }
            composable(Routes.FLASH) {
                FlashScreen(
                    appViewModel = appViewModel,
                    onOpenDrawer = openDrawer,
                    onBack = { navigateTop(Routes.CONNECT) },
                )
            }
            composable(Routes.MONITOR) {
                MonitorScreen(
                    appViewModel = appViewModel,
                    onOpenDrawer = openDrawer,
                    onBack = { navigateTop(backTarget()) },
                )
            }
            composable(Routes.RELEASES) {
                ReleasesScreen(
                    appViewModel = appViewModel,
                    onOpenDrawer = openDrawer,
                    onBack = { navigateTop(backTarget()) },
                )
            }
            composable(Routes.PROFILES) {
                ProfilesScreen(
                    appViewModel = appViewModel,
                    onOpenDrawer = openDrawer,
                    onBack = { navigateTop(backTarget()) },
                    onSelectProfile = { profile ->
                        appViewModel.loadProfileIntoPlan(profile)
                        navigateTop(Routes.FLASH)
                    },
                )
            }
        }
    }
}
