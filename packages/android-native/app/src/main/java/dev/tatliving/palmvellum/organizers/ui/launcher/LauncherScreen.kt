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
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLine
import dev.tatliving.palmvellum.organizers.ui.theme.PalmSurfaceLo

private data class LauncherApp(
    val route: String,
    val glyph: String,
    val label: String,
    val sub: String,
)

// The classic Palm core apps.
private val APPS = listOf(
    LauncherApp(Routes.DATEBOOK, "◫", "Date Book", "calendar"),
    LauncherApp(Routes.TODO, "☑", "To Do List", "tasks + due dates"),
    LauncherApp(Routes.ADDRESS, "✦", "Address", "contacts"),
    LauncherApp(Routes.MEMO, "▤", "Memo Pad", "notes"),
    LauncherApp(Routes.NOTEPAD, "✎", "Note Pad", "sketches + AI"),
    LauncherApp(Routes.EXPENSE, "¤", "Expense", "spending log"),
)

@Composable
fun LauncherScreen(navController: NavHostController) {
    val conflictCount by Graph.sync.observeConflictCount().collectAsState(0)
    PalmScaffold(
        title = "Applications",
        navController = navController,
        currentRoute = Routes.LAUNCHER,
        titleAction = {
            if (conflictCount > 0) {
                TitleAction("conflicts ($conflictCount)") { navController.navigate(Routes.CONFLICTS) }
            }
            TitleAction("settings") { navController.navigate(Routes.SETTINGS) }
        },
    ) { padding ->
        // Standard phones keep the classic two-up grid. The Cosmo Communicator's
        // wide landscape main display fits more columns, so let the grid grow to
        // fill it (adaptive ~160dp tiles => ~4 columns at 2160x1080).
        val columns = if (BuildConfig.COSMO) GridCells.Adaptive(160.dp) else GridCells.Fixed(2)
        val tileHeight = if (BuildConfig.COSMO) 140.dp else 180.dp
        LazyVerticalGrid(
            columns = columns,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(APPS) { app ->
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
                        app.label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = PalmInk,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        app.sub,
                        fontSize = 12.sp,
                        color = PalmInkMute,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
