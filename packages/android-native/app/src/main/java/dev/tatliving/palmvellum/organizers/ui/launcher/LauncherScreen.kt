package dev.tatliving.palmvellum.organizers.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.BuildConfig
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.ui.PalmScaffold
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.i18n.I18n
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLine
import dev.tatliving.palmvellum.organizers.ui.theme.PalmSurfaceLo

private data class LauncherApp(
    val route: String,
    val glyph: String,
    val labelKey: String,
    val subKey: String,
)

// The classic Palm core apps. Labels resolve through I18n at render time.
private val APPS = listOf(
    LauncherApp(Routes.DATEBOOK, "◫", "app.datebook.label", "app.datebook.sub"),
    LauncherApp(Routes.TODO, "☑", "app.todo.label", "app.todo.sub"),
    LauncherApp(Routes.ADDRESS, "✦", "app.address.label", "app.address.sub"),
    LauncherApp(Routes.MEMO, "▤", "app.memo.label", "app.memo.sub"),
    LauncherApp(Routes.NOTEPAD, "✎", "app.notepad.label", "app.notepad.sub"),
    LauncherApp(Routes.EXPENSE, "¤", "app.expense.label", "app.expense.sub"),
    LauncherApp(Routes.MAIL, "✉", "app.mail.label", "app.mail.sub"),
    // USB HotSync: host a docked Palm/CLIE over this device's USB host port and
    // sync over the cradle cable, no desktop needed. Shown on both builds —
    // portrait phones with USB-OTG can sync too (the screen reports gracefully
    // if the device has no USB host support).
    LauncherApp(Routes.HOTSYNC, "⇄", "app.hotsync.label", "app.hotsync.sub"),
)

@Composable
fun LauncherScreen(navController: NavHostController) {
    val conflictCount by Graph.sync.observeConflictCount().collectAsState(0)
    PalmScaffold(
        title = I18n.t("launcher.title"),
        navController = navController,
        currentRoute = Routes.LAUNCHER,
        titleAction = {
            if (conflictCount > 0) {
                TitleAction(I18n.t("launcher.conflicts", conflictCount)) { navController.navigate(Routes.CONFLICTS) }
            }
            TitleAction(I18n.t("launcher.settings")) { navController.navigate(Routes.SETTINGS) }
        },
    ) { padding ->
        // Standard phones keep the classic two-up grid. The Cosmo Communicator's
        // wide landscape main display fits more columns, so let the grid grow to
        // fill it (adaptive ~160dp tiles => ~4 columns at 2160x1080).
        val columns = if (BuildConfig.COSMO) GridCells.Adaptive(160.dp) else GridCells.Fixed(2)
        val tileHeight = if (BuildConfig.COSMO) 140.dp else 180.dp
        val apps = APPS
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        LazyVerticalGrid(
            columns = columns,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(apps, key = { it.route }) { app ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tileHeight)
                        .background(PalmSurfaceLo)
                        .border(1.dp, PalmLine)
                        .clickable {
                            navController.navigate(app.route) {
                                popUpTo(Routes.LAUNCHER)
                                launchSingleTop = true
                            }
                        }
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(app.glyph, fontSize = 34.sp, color = PalmInk)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        I18n.t(app.labelKey),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = PalmInk,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        I18n.t(app.subKey),
                        fontSize = 12.sp,
                        color = PalmInkMute,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
            Text(
                "PalmVellum v${BuildConfig.VERSION_NAME}",
                fontSize = 11.sp,
                color = PalmInkMute,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
    }
}
