package dev.tatliving.palmvellum.organizers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.BuildConfig
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmBg
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLine
import dev.tatliving.palmvellum.organizers.ui.theme.PalmOnDark
import dev.tatliving.palmvellum.organizers.ui.theme.PalmTitleBar

/** One of the four classic Palm Pilot silkscreen hardware buttons. */
private data class HardwareButton(val route: String, val glyph: String, val label: String)

// Left-to-right order on the original Palm Pilot hardware.
private val HARDWARE_BUTTONS = listOf(
    HardwareButton(Routes.DATEBOOK, "◫", "Date Book"),
    HardwareButton(Routes.ADDRESS, "✦", "Address"),
    HardwareButton(Routes.TODO, "☑", "To Do"),
    HardwareButton(Routes.MEMO, "▤", "Memo"),
)

/**
 * Palm-OS-style frame: dark title bar on top (with an optional trailing
 * action), the iconic four hardware buttons docked at the bottom, Palm
 * silver desk in between.
 */
@Composable
fun PalmScaffold(
    title: String,
    navController: NavHostController,
    currentRoute: String,
    titleAction: (@Composable RowScope.() -> Unit)? = null,
    // Cosmo only: skip the 760dp centring cap so a genuinely wide layout
    // (e.g. a master/detail two-pane) can use the full landscape width.
    wide: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = PalmBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PalmTitleBar(title, titleAction) },
        // Cosmo puts the four classic buttons in a left-edge icon rail instead
        // of the docked bottom row, so it has no bottom bar.
        bottomBar = { if (!BuildConfig.COSMO) PalmButtonRow(navController, currentRoute) },
    ) { padding ->
        if (BuildConfig.COSMO) {
            // Cosmo Communicator (landscape clamshell): the four core apps live
            // in a vertical icon rail down the left edge. The rest of the wide
            // display holds the content — width-capped and centred for single
            // column forms/lists, or full-bleed when `wide` (two-pane layouts).
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                PalmButtonRail(navController, currentRoute)
                if (wide) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        content(PaddingValues(0.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                            content(PaddingValues(0.dp))
                        }
                    }
                }
            }
        } else {
            // Standard portrait phone: classic docked bottom button row. The
            // width cap below never engages at phone widths, so the look is
            // unchanged from the original Palm layout.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    content(padding)
                }
            }
        }
    }
}

/**
 * List + editor screens. On the standard portrait build the editor replaces
 * the whole screen (classic Palm swap). On the Cosmo landscape build it becomes
 * a two-pane master/detail: the list stays on the left while the editor (or a
 * placeholder when nothing is selected) fills the right pane — making use of the
 * wide clamshell display. `detailContent` receives `embedded = true` in the
 * two-pane case so the editor can drop its status-bar inset.
 */
@Composable
fun <T> MasterDetailScaffold(
    title: String,
    navController: NavHostController,
    currentRoute: String,
    detail: T?,
    titleAction: (@Composable RowScope.() -> Unit)? = null,
    placeholder: String = "Pick an item from the list, or tap + new.",
    master: @Composable () -> Unit,
    detailContent: @Composable (item: T, embedded: Boolean) -> Unit,
) {
    if (!BuildConfig.COSMO) {
        if (detail != null) {
            detailContent(detail, false)
        } else {
            PalmScaffold(title, navController, currentRoute, titleAction) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { master() }
            }
        }
        return
    }
    PalmScaffold(title, navController, currentRoute, titleAction, wide = true) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxHeight()) { master() }
            Box(Modifier.width(1.dp).fillMaxHeight().background(PalmLine))
            Box(Modifier.weight(1.3f).fillMaxHeight()) {
                if (detail != null) {
                    detailContent(detail, true)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            placeholder,
                            color = PalmInkMute,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PalmTitleBar(title: String, action: (@Composable RowScope.() -> Unit)?) {
    Surface(color = PalmTitleBar, contentColor = PalmOnDark) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(44.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = PalmOnDark,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (action != null) action()
        }
    }
}

/**
 * Cosmo-only left-edge rail of the four classic hardware buttons, shown as
 * icons only (no labels) to suit the narrow vertical strip on the wide
 * landscape display.
 */
@Composable
private fun PalmButtonRail(navController: NavHostController, currentRoute: String) {
    Surface(color = PalmTitleBar, contentColor = PalmOnDark) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Start + WindowInsetsSides.Bottom,
                    ),
                )
                .width(60.dp)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HARDWARE_BUTTONS.forEach { btn ->
                val selected = btn.route == currentRoute
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            color = if (selected) Color(0x33FFFFFF) else Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable {
                            if (!selected) {
                                navController.navigate(btn.route) {
                                    popUpTo(Routes.LAUNCHER)
                                    launchSingleTop = true
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = btn.glyph, color = PalmOnDark, fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun PalmButtonRow(navController: NavHostController, currentRoute: String) {
    Surface(color = PalmTitleBar, contentColor = PalmOnDark) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(66.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HARDWARE_BUTTONS.forEach { btn ->
                val selected = btn.route == currentRoute
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(5.dp)
                        .background(
                            color = if (selected) Color(0x33FFFFFF) else Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable {
                            if (!selected) {
                                navController.navigate(btn.route) {
                                    popUpTo(Routes.LAUNCHER)
                                    launchSingleTop = true
                                }
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(text = btn.glyph, color = PalmOnDark, fontSize = 20.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(text = btn.label, color = PalmOnDark, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}
