package dev.tatliving.palmvellum.organizers.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.data.Ulid
import dev.tatliving.palmvellum.organizers.data.local.RecordEntity
import dev.tatliving.palmvellum.organizers.data.model.MailFields
import dev.tatliving.palmvellum.organizers.data.model.MailSource
import dev.tatliving.palmvellum.organizers.data.model.PalmJson
import dev.tatliving.palmvellum.organizers.data.model.mailFieldsFrom
import dev.tatliving.palmvellum.organizers.ui.MasterDetailScaffold
import dev.tatliving.palmvellum.organizers.ui.PalmScaffold
import dev.tatliving.palmvellum.organizers.ui.components.EditorScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmCategoryStrip
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.ZoneId

class MailViewModel : ViewModel() {
    private val repo = Graph.repo

    /** Inbox: AI "morning paper" digests delivered as type='mail' records. */
    val mails = repo.observeRecords("mail")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signedIn: Boolean get() = Graph.sync.isSignedIn

    fun refresh() = viewModelScope.launch { if (Graph.sync.isSignedIn) Graph.sync.syncNow() }

    /** Delete a mail digest from the inbox (soft-deletes the record + syncs). */
    fun delete(id: String) = viewModelScope.launch { repo.deleteRecord(id) }

    // ── mail_sources (subscriptions) live in their own table, online-only ──
    suspend fun loadSources(): Result<List<MailSource>> {
        val uid = Graph.session.userId ?: return Result.success(emptyList())
        return Graph.rest.select("mail_sources", "user_id=eq.$uid&order=created_at.desc").map { arr ->
            arr.mapNotNull { runCatching { PalmJson.decodeFromString<MailSource>(it.toString()) }.getOrNull() }
        }
    }

    suspend fun saveSource(src: MailSource): Result<Unit> =
        Graph.rest.upsert("mail_sources", listOf(PalmJson.encodeToJsonElement(src).jsonObject)).map { }

    suspend fun deleteSource(id: String): Result<Unit> =
        Graph.rest.delete("mail_sources", "id=eq.$id")
}

@Composable
fun MailScreen(navController: NavHostController) {
    val vm: MailViewModel = viewModel()
    val mails by vm.mails.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    var tab by remember { mutableStateOf("inbox") } // inbox | sources
    var openId by remember { mutableStateOf<String?>(null) }

    // Sources management is its own full screen, reachable via the title action.
    if (tab == "sources") {
        PalmScaffold(
            title = "Mail",
            navController = navController,
            currentRoute = Routes.MAIL,
            titleAction = { TitleAction("inbox") { tab = "inbox" } },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) { MailSources(vm) }
        }
        return
    }

    // Inbox: two-pane on Cosmo (open message left / inbox list right), classic
    // full-screen swap on standard — like the other list+detail screens.
    val open = openId?.let { id -> mails.firstOrNull { it.id == id } }
    MasterDetailScaffold(
        title = "Mail",
        navController = navController,
        currentRoute = Routes.MAIL,
        detail = open,
        titleAction = { TitleAction("sources") { tab = "sources" } },
        placeholder = "Pick a message from the inbox, or add a source.",
        master = { MailInbox(mails, signedIn = vm.signedIn, onOpen = { openId = it }) },
        detailContent = { rec, embedded ->
            MailRead(
                rec,
                embedded = embedded,
                onBack = { openId = null },
                onDelete = { vm.delete(rec.id); openId = null },
            )
        },
    )
}

@Composable
private fun MailInbox(mails: List<RecordEntity>, signedIn: Boolean, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (!signedIn) {
            Text(
                "Sign in (Settings) to subscribe to sources and receive your AI morning paper.",
                color = PalmInkMute, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (mails.isEmpty()) {
            PalmEmptyState("No mail yet. Add a subscription under 'sources' — your AI digest arrives at the time you set.")
        } else {
            val sorted = remember(mails) { mails.sortedByDescending { it.createdAt } }
            LazyColumn(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                item {
                    PalmListCard {
                        sorted.forEachIndexed { i, rec ->
                            if (i > 0) PalmDivider()
                            val f = mailFieldsFrom(rec.metadataJson)
                            val unread = rec.aiStatus in listOf("pending", "processing", "queued")
                            PalmRow(
                                title = f.mail_subject ?: "(no subject)",
                                meta = f.mail_date_local ?: rec.createdAt.take(10),
                                body = listOfNotNull(f.mail_from ?: f.mail_source_name).joinToString().ifBlank { null },
                                dim = false,
                                metaColor = if (unread) PalmRed else PalmInkMute,
                                onClick = { onOpen(rec.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailRead(rec: RecordEntity, embedded: Boolean = false, onBack: () -> Unit, onDelete: () -> Unit) {
    val f = mailFieldsFrom(rec.metadataJson)
    EditorScaffold(title = "Mail", onCancel = onBack, saveEnabled = false, embedded = embedded, onSave = {}) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
            Text(f.mail_subject ?: "(no subject)", color = PalmInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            val from = listOfNotNull(f.mail_from ?: f.mail_source_name, f.mail_date_local).joinToString(" · ")
            if (from.isNotBlank()) Text(from, color = PalmInkMute, fontSize = 12.sp)
            if (f.mail_source_type == "topic" && !f.mail_topic.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Researched: ${f.mail_topic}", color = PalmInkMute, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(rec.body.orEmpty(), color = PalmInk, fontSize = 15.sp)
            val refs = f.mail_references.orEmpty()
            if (refs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("REFERENCES", color = PalmTitleBar, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                refs.forEach { ref ->
                    Text("· $ref", color = PalmInkMute, fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            DeleteButton(onDelete)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MailSources(vm: MailViewModel) {
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<MailSource>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("url") } // url | topic
    var url by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("07:00") }
    val tz = remember { runCatching { ZoneId.systemDefault().id }.getOrDefault("UTC") }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            vm.loadSources().fold(
                { sources = it; error = null },
                { error = it.message },
            )
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Text(
            "Subscriptions",
            color = PalmInk, fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        Text(
            "A URL is fetched and summarised; a topic is researched. Your AI digest is delivered as mail at the fetch time, in your timezone.",
            color = PalmInkMute, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(8.dp))

        when {
            loading -> Text("Loading...", color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
            error != null -> Text("Error: $error", color = PalmRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
            sources.isEmpty() -> Text("No subscriptions yet.", color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
            else -> sources.forEach { src ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(src.name, color = PalmInk, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (src.source_type == "topic") src.topic else src.url).orEmpty() + "  ·  ${src.fetch_time.take(5)}",
                            color = PalmInkMute, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = src.enabled,
                        onCheckedChange = { on ->
                            scope.launch { vm.saveSource(src.copy(enabled = on)); reload() }
                        },
                    )
                    Text(
                        "remove",
                        color = PalmRed, fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            scope.launch { vm.deleteSource(src.id); reload() }
                        }.padding(6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        PalmDivider()
        Spacer(Modifier.height(8.dp))
        Text("Add a subscription", color = PalmInk, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        PalmField("Name", name, { name = it })
        Text("Type", color = PalmInkMute, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        PalmCategoryStrip(listOf("url" to "url", "topic" to "topic"), type) { type = it }
        if (type == "url") {
            PalmField("URL", url, { url = it })
        } else {
            PalmField("Topic", topic, { topic = it })
        }
        PalmField("Fetch time (HH:mm)", time, { time = it })
        Text("Timezone: $tz", color = PalmInkMute, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            val valid = name.isNotBlank() && (if (type == "url") url.isNotBlank() else topic.isNotBlank())
            Button(
                enabled = valid && !busy && vm.signedIn,
                onClick = {
                    busy = true
                    val t = time.trim().let { if (it.count { c -> c == ':' } == 1) "$it:00" else it }.ifBlank { "07:00:00" }
                    val src = MailSource(
                        id = Ulid.new(),
                        user_id = Graph.session.userId,
                        name = name.trim(),
                        source_type = type,
                        url = if (type == "url") url.trim() else null,
                        topic = if (type == "topic") topic.trim() else null,
                        fetch_time = t,
                        timezone = tz,
                        enabled = true,
                    )
                    scope.launch {
                        vm.saveSource(src).fold(
                            { name = ""; url = ""; topic = ""; error = null },
                            { error = it.message },
                        )
                        busy = false
                        reload()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
            ) { Text(if (busy) "Adding..." else "Add") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
