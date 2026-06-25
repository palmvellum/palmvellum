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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateListOf
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
import dev.tatliving.palmvellum.organizers.BuildConfig
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
import dev.tatliving.palmvellum.organizers.ui.components.TitleCategory
import dev.tatliving.palmvellum.organizers.ui.components.TitleSearch
import dev.tatliving.palmvellum.organizers.ui.i18n.I18n
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmDarkRed
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
    // Date Book AI writes events in directly — no approval step. As soon as a
    // draft is parsed, insert its events automatically (the draft then leaves
    // the active list). The set guards against re-firing while the async
    // accept is in flight.
    val autoAccepted = remember { mutableStateListOf<String>() }
    LaunchedEffect(drafts) {
        drafts.filter { it.status == "parsed" && it.id !in autoAccepted }.forEach { d ->
            autoAccepted.add(d.id)
            vm.acceptDraft(d)
        }
    }
    // null = list; otherwise the event being edited (id="" => new)
    var editing by remember { mutableStateOf<EventEntity?>(null) }
    // Cosmo's plan-with-AI input lives in the title bar (standard puts it inside
    // the new-event editor instead).
    var aiText by remember { mutableStateOf("") }
    // agenda | week | month. Both builds open on the month calendar.
    var mode by remember { mutableStateOf("month") }
    // anchor day the week/month views revolve around; selectedDay = tapped cell
    var anchor by remember { mutableStateOf(DT.nowDate()) }
    var selectedDay by remember { mutableStateOf(DT.nowDate()) }
    // Bumped to ask the agenda list to (re)scroll to today; also fires the
    // initial scroll once events have loaded.
    var jumpToToday by remember { mutableStateOf(0) }

    val target = editing
    if (target != null) {
        EventEditor(
            initial = target,
            isNew = target.id.isEmpty(),
            signedIn = vm.signedIn,
            onCancel = { editing = null },
            onSave = { vm.save(it); editing = null },
            onDelete = { vm.delete(target.id); editing = null },
            // Plan-with-AI lives inside the new-event editor: hand the text to
            // the server parser and leave the editor — the parsed events get
            // added automatically and show up on the calendar.
            onPlanWithAi = { vm.planWithAi(it); editing = null },
        )
        return
    }

    // Group events by local day, expanding any recurring events across the
    // visible window (the month grid + the next week for the agenda).
    val byDay = remember(events, anchor) { expandByDay(events, anchor) }

    val modeOptions = listOf("agenda" to I18n.t("datebook.modeAgenda"), "month" to I18n.t("datebook.modeMonth"))
    PalmScaffold(
        title = I18n.t("datebook.title"),
        navController = navController,
        currentRoute = Routes.DATEBOOK,
        titleAction = {
            // Standard agenda lists every day from the past forward, so give it a
            // Today shortcut (Cosmo's agenda already starts from today).
            if (!BuildConfig.COSMO && mode == "agenda") {
                TitleAction(I18n.t("datebook.today")) { jumpToToday++ }
            }
            TitleAction(I18n.t("common.new")) { editing = newEvent(selectedDay) }
        },
        // Cosmo: the agenda/month switcher rides in the title bar, with the
        // "plan with AI" input filling the grey space beside it. (Standard puts
        // plan-with-AI inside the new-event editor instead.)
        titleCenter = if (BuildConfig.COSMO) {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TitleCategory(modeOptions, mode) { mode = it }
                    if (vm.signedIn) {
                        TitleSearch(
                            value = aiText,
                            onValueChange = { aiText = it },
                            placeholder = I18n.t("datebook.planWithAiPlaceholder"),
                            modifier = Modifier.weight(1f),
                            onSubmit = {
                                if (aiText.isNotBlank()) { vm.planWithAi(aiText); aiText = "" }
                            },
                        )
                    }
                }
            }
        } else {
            null
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!BuildConfig.COSMO) {
                PalmCategoryStrip(
                    options = modeOptions,
                    selected = mode,
                    onSelect = { mode = it },
                )
            }
            // Plan-with-AI now lives inside the "new" event editor; surface its
            // pending/parsed drafts here (both flavors) so the calendar reflects
            // progress and results — capped so they never crowd it out.
            if (drafts.isNotEmpty()) {
                DraftStrip(vm = vm, drafts = drafts)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (mode) {
                    "month" -> {
                        // On the Cosmo's wide landscape display, show the month
                        // calendar on the right and the selected day's schedule
                        // on the left. The portrait standard build keeps the
                        // stacked calendar-over-schedule layout.
                        val monthArgs = MonthViewArgs(
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
                        if (BuildConfig.COSMO) MonthViewTwoPane(monthArgs) else MonthView(monthArgs)
                    }
                    else -> if (BuildConfig.COSMO) {
                        AgendaGridCosmo(
                            byDay = byDay,
                            onEdit = { editing = it },
                            onAdd = { day -> editing = newEvent(day) },
                        )
                    } else {
                        AgendaView(
                            events = events,
                            onEdit = { editing = it },
                            jumpToToday = jumpToToday,
                        )
                    }
                }
            }
        }
    }
}

// ── Recurrence (repeat_rule) ────────────────────────────────────────────
// repeat_rule is stored as a minimal RRULE ("FREQ=WEEKLY"); we expand the
// supported frequencies into concrete day occurrences for display only.
private val REPEAT_FREQS = setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")

private fun freqOf(rule: String?): String? {
    if (rule.isNullOrBlank()) return null
    return Regex("FREQ=([A-Z]+)", RegexOption.IGNORE_CASE)
        .find(rule)?.groupValues?.get(1)?.uppercase()?.takeIf { it in REPEAT_FREQS }
}

/** UI token (none/daily/…) ⇄ RRULE. */
private fun repeatToken(rule: String?): String = freqOf(rule)?.lowercase() ?: "none"
private fun ruleForToken(token: String): String? =
    if (token == "none") null else "FREQ=${token.uppercase()}"

private fun nextOccurrence(d: LocalDate, freq: String): LocalDate = when (freq) {
    "DAILY" -> d.plusDays(1)
    "WEEKLY" -> d.plusWeeks(1)
    "MONTHLY" -> d.plusMonths(1)
    else -> d.plusYears(1)
}

/**
 * Group events by local day across the visible window (the month grid of
 * [anchor] plus the coming week), expanding recurring events into occurrences.
 * Editing any occurrence edits the underlying base event.
 */
private fun expandByDay(
    events: List<EventEntity>,
    anchor: LocalDate,
): Map<LocalDate, List<EventEntity>> {
    val grid = DT.monthGrid(anchor)
    val from = minOf(grid.first(), DT.nowDate())
    val to = maxOf(grid.last(), DT.nowDate().plusDays(7))
    val map = HashMap<LocalDate, MutableList<EventEntity>>()
    for (e in events) {
        val start = DT.dateOf(e.startAt)
        val freq = freqOf(e.repeatRule)
        if (freq == null) {
            if (!start.isBefore(from) && !start.isAfter(to)) {
                map.getOrPut(start) { mutableListOf() }.add(e)
            }
        } else {
            var d = start
            var guard = 0
            while (d.isBefore(from) && guard < 4000) { d = nextOccurrence(d, freq); guard++ }
            while (!d.isAfter(to) && guard < 8000) {
                map.getOrPut(d) { mutableListOf() }.add(e)
                d = nextOccurrence(d, freq); guard++
            }
        }
    }
    return map
}

// ── Agenda (the upcoming-events list + AI planning) ─────────────────────
@Composable
private fun AgendaView(
    events: List<EventEntity>,
    onEdit: (EventEntity) -> Unit,
    jumpToToday: Int,
) {
    val grouped = events.groupBy { DT.dateOf(it.startAt) }.toSortedMap()
    val listState = rememberLazyListState()

    // One item per day (oldest first). Find today's row — or the next upcoming
    // day if today has no events — and scroll there on load and whenever Today
    // is tapped. (Plan-with-AI now lives above this list, at screen level.)
    val dayKeys = grouped.keys.toList()
    val today = DT.nowDate()
    val targetIndex = if (dayKeys.isEmpty()) {
        0
    } else {
        val i = dayKeys.indexOfFirst { !it.isBefore(today) }
        if (i >= 0) i else dayKeys.size - 1
    }
    LaunchedEffect(jumpToToday, dayKeys.size) {
        if (dayKeys.isNotEmpty()) listState.scrollToItem(targetIndex.coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (events.isEmpty()) {
            item(key = "empty") {
                Text(
                    I18n.t("datebook.noEvents"),
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

// ── Cosmo agenda: next 7 days, left-to-right then top-to-bottom ──────────
@Composable
private fun AgendaGridCosmo(
    byDay: Map<LocalDate, List<EventEntity>>,
    onEdit: (EventEntity) -> Unit,
    onAdd: (LocalDate) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(210.dp),
        modifier = Modifier.fillMaxSize().padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        gridItems(DT.nextDays(count = 7), key = { it.toString() }) { day ->
            DayBlock(day, byDay[day].orEmpty(), onEdit, onAdd)
        }
    }
}

/** One bordered day card: a dark-red header (brighter when today) + its events. */
@Composable
private fun DayBlock(
    day: LocalDate,
    events: List<EventEntity>,
    onEdit: (EventEntity) -> Unit,
    onAdd: (LocalDate) -> Unit,
) {
    Column(Modifier.fillMaxWidth().border(1.dp, PalmLineSoft).background(PalmSurfaceLo)) {
        DayBlockHeader(day, onAdd)
        val sorted = events.sortedBy { it.startAt }
        if (sorted.isEmpty()) {
            Text(
                I18n.t("datebook.noEventsShort"),
                color = PalmInkMute, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        } else {
            sorted.forEachIndexed { i, ev ->
                if (i > 0) PalmDivider()
                PalmRow(
                    title = ev.title,
                    meta = if (ev.allDay) I18n.t("common.allDay") else DT.timeLabel(ev.startAt) + (ev.endAt?.let { " – " + DT.timeLabel(it) } ?: ""),
                    body = ev.location,
                    metaColor = PalmRed,
                    onClick = { onEdit(ev) },
                )
            }
        }
    }
}

/** Dark-red day header used by the Cosmo agenda + week day columns. */
@Composable
private fun DayBlockHeader(day: LocalDate, onAdd: (LocalDate) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (DT.isToday(day)) PalmRed else PalmDarkRed)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                DT.weekdayFull(day).uppercase(),
                color = PalmOnDark, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
            Text(DT.fmtDate(day), color = PalmOnDark, fontSize = 11.sp)
        }
        Text(
            I18n.t("datebook.add"),
            color = PalmOnDark, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onAdd(day) }.padding(4.dp),
        )
    }
}

// ── Month view ──────────────────────────────────────────────────────────
/** Shared inputs for the month calendar + selected-day schedule. */
private class MonthViewArgs(
    val anchor: LocalDate,
    val selectedDay: LocalDate,
    val byDay: Map<LocalDate, List<EventEntity>>,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
    val onToday: () -> Unit,
    val onSelectDay: (LocalDate) -> Unit,
    val onEdit: (EventEntity) -> Unit,
    val onAdd: (LocalDate) -> Unit,
)

/** Portrait (standard): calendar grid stacked over the selected-day schedule. */
@Composable
private fun MonthView(args: MonthViewArgs) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
    ) {
        MonthCalendarGrid(args)
        Spacer(Modifier.height(12.dp))
        MonthDayDetail(args)
        Spacer(Modifier.height(24.dp))
    }
}

/** Cosmo (landscape): selected-day schedule on the left, calendar on the right. */
@Composable
private fun MonthViewTwoPane(args: MonthViewArgs) {
    Row(Modifier.fillMaxSize().padding(10.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            MonthDayDetail(args)
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Cosmo's pane is short and not scrollable, so let the grid fill the
            // available height instead of forcing square cells that overflow.
            MonthCalendarGrid(args, fillHeight = true)
        }
    }
}

/** ‹ month › nav + weekday header + the 6×7 day grid. When [fillHeight] the six
 *  week rows share the available vertical space (Cosmo); otherwise the cells stay
 *  square and the grid takes its natural height (standard, inside a scroll). */
@Composable
private fun MonthCalendarGrid(args: MonthViewArgs, fillHeight: Boolean = false) {
    val grid = DT.monthGrid(args.anchor)
    Column(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        PeriodNav(DT.monthTitle(args.anchor), args.onPrev, args.onNext, args.onToday)
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
            Row(Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier)) {
                week.forEach { day ->
                    MonthCell(
                        day = day,
                        inMonth = day.month == args.anchor.month,
                        selected = day == args.selectedDay,
                        count = args.byDay[day]?.size ?: 0,
                        fillHeight = fillHeight,
                        onClick = { args.onSelectDay(day) },
                    )
                }
            }
        }
    }
}

/** The tapped day's header (+ add) and its events. */
@Composable
private fun MonthDayDetail(args: MonthViewArgs) {
    val dayEvents = args.byDay[args.selectedDay].orEmpty().sortedBy { it.startAt }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${DT.weekdayFull(args.selectedDay)}, ${DT.fmtDate(args.selectedDay)}".uppercase(),
            color = PalmInkMute, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = 2.dp, bottom = 4.dp),
        )
        Text(
            I18n.t("datebook.add"),
            color = PalmTitleBar, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { args.onAdd(args.selectedDay) }.padding(6.dp),
        )
    }
    if (dayEvents.isEmpty()) {
        Text(I18n.t("datebook.noEventsThisDay"), color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
    } else {
        DayEventsCard(dayEvents, args.onEdit)
    }
}

@Composable
private fun RowScope.MonthCell(
    day: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    count: Int,
    fillHeight: Boolean = false,
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
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.aspectRatio(1f))
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

@Composable
private fun DayEventsCard(dayEvents: List<EventEntity>, onEdit: (EventEntity) -> Unit) {
    PalmListCard {
        dayEvents.sortedBy { it.startAt }.forEachIndexed { i, ev ->
            if (i > 0) PalmDivider()
            PalmRow(
                title = ev.title,
                meta = if (ev.allDay) I18n.t("common.allDay") else DT.timeLabel(ev.startAt) + (ev.endAt?.let { " – " + DT.timeLabel(it) } ?: ""),
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
            I18n.t("datebook.today"),
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

/**
 * The pending/parsed plan-with-AI drafts, surfaced above the calendar on both
 * flavors. Capped in height (and scrollable) so a backlog of drafts can't push
 * the calendar off-screen. The drafts auto-accept once parsed, so this is
 * mostly a brief "AI thinking…" / error indicator.
 */
@Composable
private fun DraftStrip(vm: DateBookViewModel, drafts: List<EventDraftEntity>) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 150.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        drafts.forEach { d ->
            DraftCard(
                draft = d,
                parsed = vm.parsedEventsOf(d),
                onAccept = { vm.acceptDraft(d) },
                onReject = { vm.rejectDraft(d) },
            )
            Spacer(Modifier.height(6.dp))
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
                    I18n.t("datebook.signInToPlan"),
                    color = PalmInkMute, fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                PalmField(I18n.t("datebook.planWithAi"), text, onText, singleLine = false, minLines = 2)
                Button(
                    onClick = onSubmit,
                    enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
                ) { Text(I18n.t("datebook.planWithAi")) }
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
                "pending", "parsing" -> Text(I18n.t("datebook.aiThinking"), color = PalmInkMute, fontSize = 13.sp)
                "error" -> Text(I18n.t("datebook.aiError", draft.aiError ?: ""), color = PalmRed, fontSize = 13.sp)
                "parsed" -> {
                    parsed.forEach { p ->
                        val time = p.start_at?.let { "${DT.dayLabel(it)} ${DT.timeLabel(it)}" } ?: ""
                        Text("- ${p.title}  $time", color = PalmInk, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(I18n.t("datebook.addedToDateBook"), color = PalmInkMute, fontSize = 12.sp)
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
    signedIn: Boolean = false,
    onPlanWithAi: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var aiText by remember { mutableStateOf("") }
    var title by remember { mutableStateOf(initial.title) }
    var date by remember { mutableStateOf(DT.dateOf(initial.startAt)) }
    var time by remember { mutableStateOf(DT.timeOf(initial.startAt)) }
    var endTime by remember {
        mutableStateOf(initial.endAt?.let { DT.timeOf(it) } ?: DT.timeOf(initial.startAt).plusHours(1))
    }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var location by remember { mutableStateOf(initial.location ?: "") }
    var notes by remember { mutableStateOf(initial.notes ?: "") }
    var repeat by remember { mutableStateOf(repeatToken(initial.repeatRule)) }

    EditorScaffold(
        title = if (isNew) I18n.t("datebook.newEvent") else I18n.t("datebook.editEvent"),
        onCancel = onCancel,
        saveEnabled = title.isNotBlank(),
        onSave = {
            onSave(
                initial.copy(
                    id = initial.id.ifEmpty { Ulid.new() },
                    title = title.trim(),
                    startAt = DT.toIso(date, time),
                    // Only keep an end time for timed events, and never one
                    // before the start (the cloud's events table enforces
                    // end_at >= start_at).
                    endAt = if (!allDay && !endTime.isBefore(time)) DT.toIso(date, endTime) else null,
                    allDay = allDay,
                    location = location.trim().ifEmpty { null },
                    notes = notes.trim().ifEmpty { null },
                    repeatRule = ruleForToken(repeat),
                ),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // New events can be planned with AI (free text → server-parsed
            // events) instead of, or before, filling the fields by hand. Cosmo
            // keeps plan-with-AI in the title bar, so only standard shows it here.
            if (isNew && !BuildConfig.COSMO) {
                AiPlanCard(
                    signedIn = signedIn,
                    text = aiText,
                    onText = { aiText = it },
                    onSubmit = { if (aiText.isNotBlank()) onPlanWithAi(aiText.trim()) },
                )
                PalmDivider()
                Spacer(Modifier.height(8.dp))
            }
            PalmField(I18n.t("datebook.fieldTitle"), title, { title = it })
            DateTimeRow(
                label = I18n.t("datebook.date"),
                value = DT.fmtDate(date),
                onClick = { pickDate(context, date) { date = it } },
            )
            if (!allDay) {
                DateTimeRow(
                    label = I18n.t("datebook.startTime"),
                    value = DT.fmtTime(time),
                    onClick = { pickTime(context, time) { time = it } },
                )
                DateTimeRow(
                    label = I18n.t("datebook.endTime"),
                    value = DT.fmtTime(endTime),
                    onClick = { pickTime(context, endTime) { endTime = it } },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(I18n.t("datebook.allDay"), color = PalmInk, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }
            PalmField(I18n.t("datebook.location"), location, { location = it })
            Text(I18n.t("datebook.repeats"), color = PalmInkMute, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
            PalmCategoryStrip(
                options = listOf(
                    "none" to I18n.t("datebook.repeatNone"), "daily" to I18n.t("datebook.repeatDaily"), "weekly" to I18n.t("datebook.repeatWeekly"),
                    "monthly" to I18n.t("datebook.repeatMonthly"), "yearly" to I18n.t("datebook.repeatYearly"),
                ),
                selected = repeat,
                onSelect = { repeat = it },
            )
            PalmField(I18n.t("datebook.notes"), notes, { notes = it }, singleLine = false, minLines = 3)
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
        ) { Text(I18n.t("common.delete"), color = PalmRed) }
    }
}
