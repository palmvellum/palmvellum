package dev.tatliving.palmvellum.organizers.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
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
import dev.tatliving.palmvellum.organizers.ui.components.PalmCategoryStrip
import dev.tatliving.palmvellum.organizers.ui.components.PalmDivider
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.PalmListCard
import dev.tatliving.palmvellum.organizers.ui.components.PalmRow
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLineSoft
import dev.tatliving.palmvellum.organizers.ui.theme.PalmOnDark
import dev.tatliving.palmvellum.organizers.ui.theme.PalmRed
import dev.tatliving.palmvellum.organizers.ui.theme.PalmSurfaceLo
import dev.tatliving.palmvellum.organizers.ui.theme.PalmTitleBar
import dev.tatliving.palmvellum.organizers.util.DT
import dev.tatliving.palmvellum.organizers.util.pickDate
import dev.tatliving.palmvellum.organizers.util.pickTime
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.decodeFromString

// Calendar cell accents (Palm silver desk; today gets a soft highlight).
private val CalToday = Color(0xFFFFF3C4)      // today cell / today row
private val CalWeekend = Color(0xFFD9DBD3)    // weekend cell tint

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
    // agenda | week | month
    var mode by remember { mutableStateOf("agenda") }
    // anchor day the week/month views revolve around; selectedDay = tapped cell
    var anchor by remember { mutableStateOf(DT.nowDate()) }
    var selectedDay by remember { mutableStateOf(DT.nowDate()) }

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

    // Group events by local day once; week/month look up by date.
    val byDay = remember(events) { events.groupBy { DT.dateOf(it.startAt) } }

    PalmScaffold(
        title = "Date Book",
        navController = navController,
        currentRoute = Routes.DATEBOOK,
        titleAction = { TitleAction("+ new") { editing = newEvent(selectedDay) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PalmCategoryStrip(
                options = listOf("agenda" to "agenda", "week" to "week", "month" to "month"),
                selected = mode,
                onSelect = { mode = it },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (mode) {
                    "week" -> WeekView(
                        anchor = anchor,
                        byDay = byDay,
                        onPrev = { anchor = anchor.minusWeeks(1) },
                        onNext = { anchor = anchor.plusWeeks(1) },
                        onToday = { anchor = DT.nowDate() },
                        onEdit = { editing = it },
                        onAdd = { day -> editing = newEvent(day) },
                    )
                    "month" -> MonthView(
                        anchor = anchor,
                        selectedDay = selectedDay,
                        byDay = byDay,
                        onPrev = { anchor = anchor.minusMonths(1) },
                        onNext = { anchor = anchor.plusMonths(1) },
                        onToday = { anchor = DT.nowDate(); selectedDay = DT.nowDate() },
                        onSelectDay = { selectedDay = it },
                        onEdit = { editing = it },
                        onAdd = { day -> editing = newEvent(day) },
                    )
                    else -> AgendaView(
                        vm = vm,
                        events = events,
                        drafts = drafts,
                        aiText = aiText,
                        onAiText = { aiText = it },
                        onEdit = { editing = it },
                    )
                }
            }
        }
    }
}

// ── Agenda (the upcoming-events list + AI planning) ─────────────────────
@Composable
private fun AgendaView(
    vm: DateBookViewModel,
    events: List<EventEntity>,
    drafts: List<EventDraftEntity>,
    aiText: String,
    onAiText: (String) -> Unit,
    onEdit: (EventEntity) -> Unit,
) {
    val grouped = events.groupBy { DT.dateOf(it.startAt) }.toSortedMap()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "ai") {
            AiPlanCard(
                signedIn = vm.signedIn,
                text = aiText,
                onText = onAiText,
                onSubmit = { if (aiText.isNotBlank()) { vm.planWithAi(aiText); onAiText("") } },
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
                DayEventsCard(dayEvents, onEdit)
            }
        }
    }
}

// ── Month view (6×7 grid + weekday header + selected-day events) ────────
@Composable
private fun MonthView(
    anchor: LocalDate,
    selectedDay: LocalDate,
    byDay: Map<LocalDate, List<EventEntity>>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    onEdit: (EventEntity) -> Unit,
    onAdd: (LocalDate) -> Unit,
) {
    val grid = DT.monthGrid(anchor)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
    ) {
        PeriodNav(DT.monthTitle(anchor), onPrev, onNext, onToday)
        Spacer(Modifier.height(8.dp))
        // Weekday header
        Row(Modifier.fillMaxWidth()) {
            DT.DOW_SHORT.forEachIndexed { i, d ->
                Text(
                    text = d,
                    color = if (i == 0 || i == 6) PalmRed else PalmInkMute,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                )
            }
        }
        // 6 weeks
        grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    MonthCell(
                        day = day,
                        inMonth = day.month == anchor.month,
                        selected = day == selectedDay,
                        count = byDay[day]?.size ?: 0,
                        onClick = { onSelectDay(day) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Selected day detail
        val dayEvents = byDay[selectedDay].orEmpty().sortedBy { it.startAt }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${DT.weekdayFull(selectedDay)}, ${DT.fmtDate(selectedDay)}".uppercase(),
                color = PalmInkMute, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 2.dp, bottom = 4.dp),
            )
            Text(
                "+ add",
                color = PalmTitleBar, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onAdd(selectedDay) }.padding(6.dp),
            )
        }
        if (dayEvents.isEmpty()) {
            Text("No events this day.", color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        } else {
            DayEventsCard(dayEvents, onEdit)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RowScope.MonthCell(
    day: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit,
) {
    val bg = when {
        DT.isToday(day) -> CalToday
        DT.isWeekend(day) -> CalWeekend
        else -> PalmSurfaceLo
    }
    Box(
        Modifier
            .weight(1f)
            .aspectRatio(1f)
            .padding(1.dp)
            .background(bg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) PalmTitleBar else PalmLineSoft,
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            text = day.dayOfMonth.toString(),
            color = if (inMonth) PalmInk else PalmInkMute,
            fontSize = 12.sp,
            fontWeight = if (DT.isToday(day)) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp),
        )
        if (count > 0) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(minOf(count, 3)) {
                    Box(Modifier.size(5.dp).background(PalmRed, CircleShape))
                }
                if (count > 3) {
                    Text("+", color = PalmRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Week view (7 day blocks, Sunday-first) ──────────────────────────────
@Composable
private fun WeekView(
    anchor: LocalDate,
    byDay: Map<LocalDate, List<EventEntity>>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onEdit: (EventEntity) -> Unit,
    onAdd: (LocalDate) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
    ) {
        PeriodNav(DT.weekTitle(anchor), onPrev, onNext, onToday)
        Spacer(Modifier.height(8.dp))
        DT.weekDays(anchor).forEach { day ->
            val dayEvents = byDay[day].orEmpty().sortedBy { it.startAt }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    "${DT.weekdayFull(day)} · ${DT.fmtDate(day)}".uppercase(),
                    color = if (DT.isToday(day)) PalmInk else PalmInkMute,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 2.dp, bottom = 4.dp),
                )
                Text(
                    "+ add",
                    color = PalmTitleBar, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onAdd(day) }.padding(6.dp),
                )
            }
            if (dayEvents.isEmpty()) {
                Text("—", color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            } else {
                DayEventsCard(dayEvents, onEdit)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DayEventsCard(dayEvents: List<EventEntity>, onEdit: (EventEntity) -> Unit) {
    PalmListCard {
        dayEvents.sortedBy { it.startAt }.forEachIndexed { i, ev ->
            if (i > 0) PalmDivider()
            PalmRow(
                title = ev.title,
                meta = if (ev.allDay) "all day" else DT.timeLabel(ev.startAt),
                body = ev.location,
                metaColor = PalmRed,
                onClick = { onEdit(ev) },
            )
        }
    }
}

/** ‹  title  › with a Today shortcut — drives week/month navigation. */
@Composable
private fun PeriodNav(title: String, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        NavChip("‹", onPrev)
        Text(
            title,
            color = PalmInk, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Text(
            "today",
            color = PalmTitleBar, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onToday).padding(horizontal = 8.dp, vertical = 6.dp),
        )
        NavChip("›", onNext)
    }
}

@Composable
private fun NavChip(glyph: String, onClick: () -> Unit) {
    Text(
        glyph,
        color = PalmOnDark, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .size(36.dp)
            .background(PalmTitleBar)
            .clickable(onClick = onClick)
            .padding(top = 6.dp),
    )
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

private fun newEvent(day: LocalDate = DT.nowDate()): EventEntity {
    val now = Clock.nowIso()
    return EventEntity(
        id = "",
        title = "",
        startAt = DT.toIso(day, DT.nowTime()),
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
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PalmRed),
            border = BorderStroke(1.dp, PalmRed),
        ) { Text("delete", color = PalmRed) }
    }
}
