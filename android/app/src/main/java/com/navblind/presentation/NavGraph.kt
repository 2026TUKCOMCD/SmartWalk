package com.navblind.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.navblind.presentation.auth.LoginScreen
import com.navblind.presentation.navigation.NavigationScreen
import com.navblind.presentation.settings.DevicePairingScreen
import com.navblind.presentation.settings.PreferencesScreen
import com.navblind.presentation.settings.SavedDestinationsScreen

object NavRoutes {
    const val NAVIGATION = "navigation"
    const val LOGIN = "login"
    const val DEVICE_PAIRING = "device_pairing"
    const val PREFERENCES = "preferences"
    const val SAVED_DESTINATIONS = "saved_destinations"
}

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = NavRoutes.NAVIGATION
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(NavRoutes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(NavRoutes.NAVIGATION) {
                        popUpTo(NavRoutes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.NAVIGATION) {
            NavigationScreen(
                onNavigateToSettings = { navController.navigate(NavRoutes.PREFERENCES) }
            )
        }

        composable(NavRoutes.DEVICE_PAIRING) {
            DevicePairingScreen(onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.PREFERENCES) {
            PreferencesScreen(onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.SAVED_DESTINATIONS) {
            SavedDestinationsScreen(onBack = { navController.popBackStack() })
        }
    }
}
