package dev.tatliving.palmvellum.organizers.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.data.Clock
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.data.Ulid
import dev.tatliving.palmvellum.organizers.data.local.EventDraftEntity
import dev.tatliving.palmvellum.organizers.data.local.EventEntity
import dev.tatliving.palmvellum.organizers.data.local.ParsedEvent
import dev.tatliving.palmvellum.organizers.data.model.PalmJson
import dev.tatliving.palmvellum.organizers.ui.PalmScaffold
import dev.tatliving.palmvellum.organizers.ui.components.EditorScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmDivider
import dev.tatliving.palmvellum.organizers.ui.components.PalmEmptyState
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.PalmListCard
import dev.tatliving.palmvellum.organizers.ui.components.PalmRow
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmRed
import dev.tatliving.palmvellum.organizers.ui.theme.PalmTitleBar
import dev.tatliving.palmvellum.organizers.util.DT
import dev.tatliving.palmvellum.organizers.util.pickDate
import dev.tatliving.palmvellum.organizers.util.pickTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.decodeFromString

class DateBookViewModel : ViewModel() {
    private val repo = Graph.repo
    val events = repo.observeEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val drafts = repo.observeDrafts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signedIn: Boolean get() = Graph.sync.isSignedIn

    fun save(e: EventEntity) = viewModelScope.launch { repo.saveEvent(e) }
    fun delete(id: String) = viewModelScope.launch { repo.deleteEvent(id) }

    /** "Plan with AI" — create a draft the server parses into events. */
    fun planWithAi(text: String) = viewModelScope.launch {
        repo.createDraft(text, ZoneId.systemDefault().id)
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    }

    fun acceptDraft(d: EventDraftEntity) = viewModelScope.launch {
        val parsed = runCatching { PalmJson.decodeFromString<List<ParsedEvent>>(d.parsedEventsJson) }
            .getOrDefault(emptyList())
        repo.acceptDraft(d, parsed)
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    }

    fun rejectDraft(d: EventDraftEntity) = viewModelScope.launch {
        repo.rejectDraft(d)
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    }

    fun refresh() = viewModelScope.launch { if (Graph.sync.isSignedIn) Graph.sync.syncNow() }

    fun parsedEventsOf(d: EventDraftEntity): List<ParsedEvent> =
        runCatching { PalmJson.decodeFromString<List<ParsedEvent>>(d.parsedEventsJson) }.getOrDefault(emptyList())
}

@Composable
fun DateBookScreen(navController: NavHostController) {
    val vm: DateBookViewModel = viewModel()
    val events by vm.events.collectAsState()
    val drafts by vm.drafts.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    // null = list; otherwise the event being edited (id="" => new)
    var editing by remember { mutableStateOf<EventEntity?>(null) }
    var aiText by remember { mutableStateOf("") }

    val target = editing
    if (target != null) {
        EventEditor(
            initial = target,
            isNew = target.id.isEmpty(),
            onCancel = { editing = null },
            onSave = { vm.save(it); editing = null },
            onDelete = { vm.delete(target.id); editing = null },
        )
        return
    }

    PalmScaffold(
        title = "Date Book",
        navController = navController,
        currentRoute = Routes.DATEBOOK,
        titleAction = { TitleAction("+ new") { editing = newEvent() } },
    ) { padding ->
        val grouped = events.groupBy { DT.dateOf(it.startAt) }.toSortedMap()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "ai") {
                AiPlanCard(
                    signedIn = vm.signedIn,
                    text = aiText,
                    onText = { aiText = it },
                    onSubmit = { if (aiText.isNotBlank()) { vm.planWithAi(aiText); aiText = "" } },
                )
            }
            items(drafts, key = { it.id }) { d ->
                DraftCard(
                    draft = d,
                    parsed = vm.parsedEventsOf(d),
                    onAccept = { vm.acceptDraft(d) },
                    onReject = { vm.rejectDraft(d) },
                )
            }
            if (events.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No events yet. Tap + new, or plan with AI above.",
                        color = PalmInkMute, fontSize = 14.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            grouped.forEach { (day, dayEvents) ->
                item(key = day.toString()) {
                    Text(
                        DT.dayLabel(dayEvents.first().startAt).uppercase(),
                        color = PalmInkMute,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                    )
                    PalmListCard {
                        dayEvents.sortedBy { it.startAt }.forEachIndexed { i, ev ->
                            if (i > 0) PalmDivider()
                            PalmRow(
                                title = ev.title,
                                meta = if (ev.allDay) "all day" else DT.timeLabel(ev.startAt),
                                body = ev.location,
                                metaColor = PalmRed,
                                onClick = { editing = ev },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiPlanCard(
    signedIn: Boolean,
    text: String,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    PalmListCard {
        Column(Modifier.padding(4.dp)) {
            if (!signedIn) {
                Text(
                    "Sign in (Settings) to plan events with AI.",
                    color = PalmInkMute, fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                PalmField("Plan with AI", text, onText, singleLine = false, minLines = 2)
                Button(
                    onClick = onSubmit,
                    enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
                ) { Text("Plan with AI") }
            }
        }
    }
}

@Composable
private fun DraftCard(
    draft: EventDraftEntity,
    parsed: List<ParsedEvent>,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    PalmListCard {
        Column(Modifier.padding(12.dp)) {
            Text("\"${draft.rawInput}\"", color = PalmInk, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            when (draft.status) {
                "pending", "parsing" -> Text("AI thinking...", color = PalmInkMute, fontSize = 13.sp)
                "error" -> Text("AI error: ${draft.aiError ?: ""}", color = PalmRed, fontSize = 13.sp)
                "parsed" -> {
                    parsed.forEach { p ->
                        val time = p.start_at?.let { "${DT.dayLabel(it)} ${DT.timeLabel(it)}" } ?: ""
                        Text("- ${p.title}  $time", color = PalmInk, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
                        ) { Text("Add all") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onReject) { Text("Dismiss") }
                    }
                }
                else -> Text(draft.status, color = PalmInkMute, fontSize = 13.sp)
            }
        }
    }
}

private fun newEvent(): EventEntity {
    val now = Clock.nowIso()
    return EventEntity(
        id = "",
        title = "",
        startAt = DT.toIso(DT.nowDate(), DT.nowTime()),
        createdAt = now,
        updatedAt = now,
    )
}

@Composable
private fun EventEditor(
    initial: EventEntity,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (EventEntity) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initial.title) }
    var date by remember { mutableStateOf(DT.dateOf(initial.startAt)) }
    var time by remember { mutableStateOf(DT.timeOf(initial.startAt)) }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var location by remember { mutableStateOf(initial.location ?: "") }
    var notes by remember { mutableStateOf(initial.notes ?: "") }

    EditorScaffold(
        title = if (isNew) "New Event" else "Edit Event",
        onCancel = onCancel,
        saveEnabled = title.isNotBlank(),
        onSave = {
            onSave(
                initial.copy(
                    id = initial.id.ifEmpty { Ulid.new() },
                    title = title.trim(),
                    startAt = DT.toIso(date, time),
                    allDay = allDay,
                    location = location.trim().ifEmpty { null },
                    notes = notes.trim().ifEmpty { null },
                ),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PalmField("Title", title, { title = it })
            DateTimeRow(
                label = "Date",
                value = DT.fmtDate(date),
                onClick = { pickDate(context, date) { date = it } },
            )
            if (!allDay) {
                DateTimeRow(
                    label = "Time",
                    value = DT.fmtTime(time),
                    onClick = { pickTime(context, time) { time = it } },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("All day", color = PalmInk, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }
            PalmField("Location", location, { location = it })
            PalmField("Notes", notes, { notes = it }, singleLine = false, minLines = 3)
            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                DeleteButton(onDelete)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun DateTimeRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, color = PalmInkMute, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        PalmListCard {
            PalmRow(title = value, onClick = onClick)
        }
    }
}

@Composable
internal fun DeleteButton(onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        androidx.compose.material3.OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = PalmRed),
            border = androidx.compose.foundation.BorderStroke(1.dp, PalmRed),
        ) { Text("delete", color = PalmRed) }
    }
}
