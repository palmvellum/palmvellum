package dev.tatliving.palmvellum.organizers.ui

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import dev.tatliving.palmvellum.organizers.BuildConfig
import dev.tatliving.palmvellum.organizers.MainActivity
import dev.tatliving.palmvellum.organizers.data.CalRefreshWorker
import dev.tatliving.palmvellum.organizers.data.CalendarSync
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.ui.nav.PalmNavHost
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmVellumTheme

@Composable
fun PalmVellumRoot() {
    PalmVellumTheme {
        // Opt-in cloud sync: if a session exists, sync once on launch.
        // No-op (and no network) when signed out — the app is local-first.
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            if (Graph.sync.isSignedIn) Graph.sync.syncNow()
            // Best-effort refresh of any read-only calendar subscriptions, and
            // (re)schedule the periodic background refresh to the chosen interval.
            runCatching { CalendarSync.refresh(context) }
            runCatching { CalRefreshWorker.schedule(context) }
        }
        val navController = rememberNavController()

        // Plugging in a Palm/CLIE cradle launches us via USB_DEVICE_ATTACHED;
        // MainActivity bumps a counter, and we jump straight to HotSync so the
        // user lands on Start. Re-attaching while open re-fires through the same
        // counter (the activity is singleTop). Cosmo-only — only it has the tile.
        val mainActivity = context as? MainActivity
        if (mainActivity != null) {
            val request = mainActivity.hotSyncRequest
            LaunchedEffect(request) {
                if (request > 0) {
                    navController.navigate(Routes.HOTSYNC) {
                        popUpTo(Routes.LAUNCHER); launchSingleTop = true
                    }
                }
            }
        }

        // Cosmo physical-keyboard shortcuts: Ctrl+1..7 jump straight to an app,
        // Ctrl+0 / Ctrl+H go Home, Esc goes back. Ctrl-modified combos and Esc
        // don't collide with normal typing, so text fields keep every key.
        if (BuildConfig.COSMO) {
            val activity = context as? MainActivity
            DisposableEffect(navController) {
                fun toApp(route: String) = navController.navigate(route) {
                    popUpTo(Routes.LAUNCHER); launchSingleTop = true
                }
                fun goHome() = navController.navigate(Routes.LAUNCHER) {
                    popUpTo(Routes.LAUNCHER) { inclusive = true }; launchSingleTop = true
                }
                activity?.keyHandler = handler@{ ev ->
                    if (ev.action != KeyEvent.ACTION_DOWN && ev.action != KeyEvent.ACTION_UP) return@handler false
                    val down = ev.action == KeyEvent.ACTION_DOWN
                    if (ev.isCtrlPressed) {
                        val route = when (ev.keyCode) {
                            KeyEvent.KEYCODE_1 -> Routes.DATEBOOK
                            KeyEvent.KEYCODE_2 -> Routes.TODO
                            KeyEvent.KEYCODE_3 -> Routes.ADDRESS
                            KeyEvent.KEYCODE_4 -> Routes.MEMO
                            KeyEvent.KEYCODE_5 -> Routes.NOTEPAD
                            KeyEvent.KEYCODE_6 -> Routes.EXPENSE
                            KeyEvent.KEYCODE_7 -> Routes.MAIL
                            else -> null
                        }
                        when {
                            route != null -> { if (down) toApp(route); return@handler true }
                            ev.keyCode == KeyEvent.KEYCODE_0 || ev.keyCode == KeyEvent.KEYCODE_H -> {
                                if (down) goHome(); return@handler true
                            }
                            else -> return@handler false
                        }
                    }
                    if (ev.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        if (down) navController.popBackStack()
                        return@handler true
                    }
                    false
                }
                onDispose { activity?.keyHandler = null }
            }
        }

        PalmNavHost(navController = navController)
    }
}
