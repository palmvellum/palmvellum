package dev.tatliving.palmvellum.organizers.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.data.local.ConflictEntity
import dev.tatliving.palmvellum.organizers.ui.PalmScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmDivider
import dev.tatliving.palmvellum.organizers.ui.components.PalmEmptyState
import dev.tatliving.palmvellum.organizers.ui.components.PalmListCard
import dev.tatliving.palmvellum.organizers.ui.components.PalmRow
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.i18n.I18n
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmBg
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLine
import dev.tatliving.palmvellum.organizers.ui.theme.PalmOnDark
import dev.tatliving.palmvellum.organizers.ui.theme.PalmSurfaceHi
import dev.tatliving.palmvellum.organizers.ui.theme.PalmTitleBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConflictsViewModel : ViewModel() {
    private val sync = Graph.sync
    val conflicts = sync.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun resolve(c: ConflictEntity, keepLocal: Boolean) =
        viewModelScope.launch { sync.resolveConflict(c, keepLocal) }
}

@Composable
fun ConflictsScreen(navController: NavHostController) {
    val vm: ConflictsViewModel = viewModel()
    val conflicts by vm.conflicts.collectAsState()
    var selected by remember { mutableStateOf<ConflictEntity?>(null) }

    val sel = selected
    if (sel != null) {
        ConflictDetail(
            conflict = sel,
            onKeepLocal = { vm.resolve(sel, true); selected = null },
            onKeepRemote = { vm.resolve(sel, false); selected = null },
            onBack = { selected = null },
        )
        return
    }

    PalmScaffold(
        title = I18n.t("conflicts.title"),
        navController = navController,
        currentRoute = Routes.CONFLICTS,
        titleAction = {
            TitleAction(I18n.t("common.home")) {
                navController.navigate(Routes.LAUNCHER) { popUpTo(Routes.LAUNCHER) { inclusive = true } }
            }
        },
    ) { padding ->
        if (conflicts.isEmpty()) {
            PalmEmptyState(I18n.t("conflicts.empty"))
            return@PalmScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(10.dp)) {
            item {
                Text(
                    I18n.t("conflicts.explain"),
                    color = PalmInkMute, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp, start = 2.dp),
                )
                PalmListCard {
                    conflicts.forEachIndexed { i, c ->
                        if (i > 0) PalmDivider()
                        PalmRow(
                            title = c.titleHint.ifBlank { I18n.t("conflicts.itemFallback") },
                            meta = c.entityType,
                            body = I18n.t("conflicts.editedBoth"),
                            onClick = { selected = c },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictDetail(
    conflict: ConflictEntity,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(color = PalmBg) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = PalmTitleBar, contentColor = PalmOnDark) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(44.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(I18n.t("common.back"), color = PalmOnDark, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onBack))
                    Text(
                        I18n.t("conflicts.resolveTitle"),
                        color = PalmOnDark,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(conflict.titleHint, color = PalmInk, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                SnapshotBlock(I18n.t("conflicts.thisDevice"), conflict.localUpdatedAt, conflict.localJson)
                Spacer(Modifier.height(10.dp))
                SnapshotBlock(I18n.t("conflicts.cloud"), conflict.remoteUpdatedAt, conflict.remoteJson)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onKeepLocal,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
                ) { Text(I18n.t("conflicts.keepThisDevice")) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onKeepRemote,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
                ) { Text(I18n.t("conflicts.keepCloud")) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SnapshotBlock(label: String, updatedAt: String, json: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PalmSurfaceHi)
            .border(1.dp, PalmLine)
            .padding(10.dp),
    ) {
        Text(label, color = PalmInk, fontSize = 14.sp)
        Text(I18n.t("conflicts.updated", updatedAt), color = PalmInkMute, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Text(json, color = PalmInk, fontSize = 12.sp)
    }
}
