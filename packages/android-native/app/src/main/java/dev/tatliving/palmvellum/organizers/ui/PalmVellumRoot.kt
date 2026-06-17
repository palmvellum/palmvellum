package dev.tatliving.palmvellum.organizers.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import dev.tatliving.palmvellum.organizers.data.CalendarSync
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.ui.nav.PalmNavHost
import dev.tatliving.palmvellum.organizers.ui.theme.PalmVellumTheme

@Composable
fun PalmVellumRoot() {
    PalmVellumTheme {
        // Opt-in cloud sync: if a session exists, sync once on launch.
        // No-op (and no network) when signed out — the app is local-first.
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            if (Graph.sync.isSignedIn) Graph.sync.syncNow()
            // Best-effort refresh of any read-only calendar subscriptions.
            runCatching { CalendarSync.refresh(context) }
        }
        val navController = rememberNavController()
        PalmNavHost(navController = navController)
    }
}
