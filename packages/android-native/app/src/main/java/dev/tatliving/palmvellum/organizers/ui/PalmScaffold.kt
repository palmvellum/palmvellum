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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmBg
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
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = PalmBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PalmTitleBar(title, titleAction) },
        bottomBar = { PalmButtonRow(navController, currentRoute) },
    ) { padding ->
        // On a wide display (the Cosmo Communicator's landscape main screen)
        // a full-bleed single column of forms/lists stretches uncomfortably far.
        // Centre the content and cap its width. On a normal portrait phone this
        // cap never engages, so the standard build is unaffected.
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
