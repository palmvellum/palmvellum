package dev.tatliving.palmvellum.organizers.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.ui.nav.PalmNavHost
import dev.tatliving.palmvellum.organizers.ui.theme.PalmVellumTheme

@Composable
fun PalmVellumRoot() {
    PalmVellumTheme {
        // Opt-in cloud sync: if a session exists, sync once on launch.
        // No-op (and no network) when signed out — the app is local-first.
        LaunchedEffect(Unit) {
            if (Graph.sync.isSignedIn) Graph.sync.syncNow()
        }
        val navController = rememberNavController()
        PalmNavHost(navController = navController)
    }
}
