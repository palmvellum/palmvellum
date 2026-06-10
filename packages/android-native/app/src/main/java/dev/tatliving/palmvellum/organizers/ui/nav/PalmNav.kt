package dev.tatliving.palmvellum.organizers.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.tatliving.palmvellum.organizers.ui.launcher.LauncherScreen
import dev.tatliving.palmvellum.organizers.ui.screens.AddressScreen
import dev.tatliving.palmvellum.organizers.ui.screens.ConflictsScreen
import dev.tatliving.palmvellum.organizers.ui.screens.DateBookScreen
import dev.tatliving.palmvellum.organizers.ui.screens.MemoScreen
import dev.tatliving.palmvellum.organizers.ui.screens.SettingsScreen
import dev.tatliving.palmvellum.organizers.ui.screens.TodoScreen

object Routes {
    const val LAUNCHER = "launcher"
    const val DATEBOOK = "datebook"
    const val ADDRESS = "address"
    const val TODO = "todo"
    const val MEMO = "memo"
    const val SETTINGS = "settings"
    const val CONFLICTS = "conflicts"
}

@Composable
fun PalmNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.LAUNCHER) {
        composable(Routes.LAUNCHER) { LauncherScreen(navController) }
        composable(Routes.DATEBOOK) { DateBookScreen(navController) }
        composable(Routes.ADDRESS) { AddressScreen(navController) }
        composable(Routes.TODO) { TodoScreen(navController) }
        composable(Routes.MEMO) { MemoScreen(navController) }
        composable(Routes.SETTINGS) { SettingsScreen(navController) }
        composable(Routes.CONFLICTS) { ConflictsScreen(navController) }
    }
}
