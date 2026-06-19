package dev.tatliving.palmvellum.organizers.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.BuildConfig
import dev.tatliving.palmvellum.organizers.data.Clock
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.data.Ulid
import dev.tatliving.palmvellum.organizers.data.local.RecordEntity
import dev.tatliving.palmvellum.organizers.data.model.TodoFields
import dev.tatliving.palmvellum.organizers.data.model.todoFieldsFrom
import dev.tatliving.palmvellum.organizers.data.model.toJson
import dev.tatliving.palmvellum.organizers.ui.MasterDetailScaffold
import dev.tatliving.palmvellum.organizers.ui.components.EditorScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmCategoryStrip
import dev.tatliving.palmvellum.organizers.ui.components.PalmDivider
import dev.tatliving.palmvellum.organizers.ui.components.PalmEmptyState
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.PalmListCard
import dev.tatliving.palmvellum.organizers.ui.components.PalmRow
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.components.TitleCategory
import dev.tatliving.palmvellum.organizers.ui.i18n.I18n
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.util.DT
import dev.tatliving.palmvellum.organizers.util.pickDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodoViewModel : ViewModel() {
    private val repo = Graph.repo
    val todos = repo.observeRecords("todo")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(r: RecordEntity) = viewModelScope.launch {
        val withAi = if (isAiRequest(r.body)) r.copy(aiStatus = "pending") else r
        repo.saveRecord(withAi)
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    }

    fun delete(id: String) = viewModelScope.launch { repo.deleteRecord(id) }

    fun refresh() = viewModelScope.launch { if (Graph.sync.isSignedIn) Graph.sync.syncNow() }
}

@Composable
fun TodoScreen(navController: NavHostController) {
    val vm: TodoViewModel = viewModel()
    val todos by vm.todos.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    var filter by remember { mutableStateOf("open") } // open | done | all
    var editing by remember { mutableStateOf<RecordEntity?>(null) }
    val filterOptions = listOf(
        "open" to I18n.t("todo.filterOpen"),
        "done" to I18n.t("todo.filterDone"),
        "all" to I18n.t("todo.filterAll"),
    )

    MasterDetailScaffold(
        title = I18n.t("todo.title"),
        navController = navController,
        currentRoute = Routes.TODO,
        detail = editing,
        titleAction = { TitleAction(I18n.t("common.new")) { editing = newTodo() } },
        // Cosmo: the open/done/all filter rides in the title bar to save height.
        titleCenter = if (BuildConfig.COSMO) {
            { TitleCategory(filterOptions, filter) { filter = it } }
        } else {
            null
        },
        placeholder = I18n.t("todo.placeholder"),
        master = {
            Column(Modifier.fillMaxSize()) {
                if (!BuildConfig.COSMO) {
                    PalmCategoryStrip(
                        options = filterOptions,
                        selected = filter,
                        onSelect = { filter = it },
                    )
                }
                // Memoised so toggling/selecting a task doesn't re-filter every tap.
                val visible = remember(todos, filter) {
                    todos.filter {
                        val done = todoFieldsFrom(it.metadataJson).palm_completed
                        when (filter) {
                            "open" -> !done
                            "done" -> done
                            else -> true
                        }
                    }
                }
                if (visible.isEmpty()) {
                    PalmEmptyState(I18n.t("todo.noTasks"))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                    ) {
                        item {
                            PalmListCard {
                                visible.forEachIndexed { i, rec ->
                                    if (i > 0) PalmDivider()
                                    val f = todoFieldsFrom(rec.metadataJson)
                                    PalmRow(
                                        title = rec.body ?: I18n.t("todo.untitled"),
                                        meta = buildList {
                                            if (rec.aiStatus in listOf("pending", "processing", "queued")) add(I18n.t("todo.aiBusy"))
                                            f.palm_priority?.let { add(I18n.t("todo.priorityShort", it)) }
                                            f.palm_due_date?.let { add(it) }
                                        }.joinToString("  ").ifEmpty { null },
                                        dim = f.palm_completed,
                                        leading = {
                                            Checkbox(
                                                checked = f.palm_completed,
                                                onCheckedChange = { checked ->
                                                    vm.save(rec.copy(metadataJson = f.copy(palm_completed = checked).toJson()))
                                                },
                                            )
                                        },
                                        onClick = { editing = rec },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        detailContent = { target, embedded ->
            // Key on the record id so tapping another task re-inits the editor.
            key(target.id) {
                TodoEditor(
                    initial = target,
                    isNew = target.id.isEmpty(),
                    embedded = embedded,
                    onCancel = { editing = null },
                    onSave = { vm.save(it); editing = null },
                    onDelete = { vm.delete(target.id); editing = null },
                )
            }
        },
    )
}

private fun newTodo(): RecordEntity {
    val now = Clock.nowIso()
    return RecordEntity(id = "", type = "todo", body = "", createdAt = now, updatedAt = now)
}

@Composable
private fun TodoEditor(
    initial: RecordEntity,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (RecordEntity) -> Unit,
    onDelete: () -> Unit,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val f0 = todoFieldsFrom(initial.metadataJson)
    var desc by remember { mutableStateOf(initial.body ?: "") }
    var priority by remember { mutableStateOf(f0.palm_priority) }
    var due by remember { mutableStateOf(f0.palm_due_date) }
    var notes by remember { mutableStateOf(f0.palm_notes ?: "") }

    EditorScaffold(
        title = if (isNew) I18n.t("todo.newTask") else I18n.t("todo.editTask"),
        onCancel = onCancel,
        embedded = embedded,
        saveEnabled = desc.isNotBlank(),
        onSave = {
            val fields = TodoFields(
                palm_due_date = due,
                palm_priority = priority,
                palm_completed = f0.palm_completed,
                palm_notes = notes.trim().ifEmpty { null },
                palm_category_name = f0.palm_category_name,
            )
            onSave(
                initial.copy(
                    id = initial.id.ifEmpty { Ulid.new() },
                    body = desc.trim(),
                    metadataJson = fields.toJson(),
                ),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PalmField(I18n.t("todo.descriptionHint"), desc, { desc = it }, singleLine = false, minLines = 2)
            // Priority 1..5
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(I18n.t("todo.priority"), color = PalmInkMute, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { p ->
                        val active = priority == p
                        Text(
                            I18n.t("todo.priorityShort", p),
                            color = if (active) PalmInk else PalmInkMute,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .clickable { priority = if (active) null else p }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            DateTimeRow(
                label = if (due == null) I18n.t("todo.dueDateNone") else I18n.t("todo.dueDate"),
                value = due ?: I18n.t("todo.setDueDate"),
                onClick = { pickDate(context, DT.nowDate()) { due = DT.fmtDate(it) } },
            )
            if (due != null) {
                Text(
                    I18n.t("todo.clearDueDate"),
                    color = PalmInkMute,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp).clickable { due = null },
                )
            }
            PalmField(I18n.t("todo.notes"), notes, { notes = it }, singleLine = false, minLines = 3)
            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                DeleteButton(onDelete)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
