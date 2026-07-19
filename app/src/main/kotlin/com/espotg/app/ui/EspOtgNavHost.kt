package com.espotg.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.espotg.app.ui.connect.ConnectScreen
import com.espotg.app.ui.flash.FlashScreen
import com.espotg.app.ui.monitor.MonitorScreen
import com.espotg.app.ui.profiles.ProfilesScreen
import com.espotg.app.ui.releases.ReleasesScreen

private object Routes {
    const val CONNECT = "connect"
    const val FLASH = "flash"
    const val MONITOR = "monitor"
    const val PROFILES = "profiles"
    const val RELEASES = "releases"
}

@Composable
fun EspOtgNavHost(appViewModel: AppViewModel, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.CONNECT) {
        composable(Routes.CONNECT) {
            ConnectScreen(
                appViewModel = appViewModel,
                onConnected = { navController.navigate(Routes.FLASH) },
                onOpenProfiles = { navController.navigate(Routes.PROFILES) },
                onOpenMonitor = { navController.navigate(Routes.MONITOR) },
            )
        }
        composable(Routes.FLASH) {
            FlashScreen(
                appViewModel = appViewModel,
                onOpenMonitor = { navController.navigate(Routes.MONITOR) },
                onOpenReleases = { navController.navigate(Routes.RELEASES) },
            )
        }
        composable(Routes.MONITOR) {
            MonitorScreen(appViewModel = appViewModel)
        }
        composable(Routes.RELEASES) {
            ReleasesScreen(appViewModel = appViewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.PROFILES) {
            ProfilesScreen(
                appViewModel = appViewModel,
                onBack = { navController.popBackStack() },
                onSelectProfile = { profile ->
                    appViewModel.loadProfileIntoPlan(profile)
                    navController.navigate(Routes.FLASH)
                },
            )
        }
    }
}
