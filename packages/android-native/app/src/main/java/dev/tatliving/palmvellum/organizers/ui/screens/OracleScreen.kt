package dev.tatliving.palmvellum.organizers.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
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
import dev.tatliving.palmvellum.organizers.data.local.RecordEntity
import dev.tatliving.palmvellum.organizers.ui.PalmScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmDivider
import dev.tatliving.palmvellum.organizers.ui.components.PalmEmptyState
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.PalmListCard
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLineSoft
import dev.tatliving.palmvellum.organizers.ui.theme.PalmRed
import dev.tatliving.palmvellum.organizers.ui.theme.PalmTitleBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Oracle — ask the cloud AI a free-form question. Each question is a
 * `type='aiquery'` record: the body holds the question, `ai_status='pending'`
 * triggers the server (`process-ai-queue` Edge Function, using the user's
 * BYOK key from Vault), and the answer comes back in the `ai_response`
 * column on the next pull. No new table or migration — the sync layer
 * already pushes ai_status and pulls ai_response.
 */
class OracleViewModel : ViewModel() {
    private val repo = Graph.repo
    val queries = repo.observeRecords("aiquery")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Enqueue a question for the server-side Oracle. */
    fun ask(question: String) = viewModelScope.launch {
        val now = Clock.nowIso()
        repo.saveRecord(
            RecordEntity(
                id = Ulid.new(),
                type = "aiquery",
                body = question.trim(),
                aiStatus = "pending",
                createdAt = now,
                updatedAt = now,
            ),
        )
        // Push immediately so the Oracle can start; the answer returns on a later pull.
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    }

    /** Pull on entry so answers (and other devices' questions) appear. */
    fun refresh() = viewModelScope.launch { if (Graph.sync.isSignedIn) Graph.sync.syncNow() }
}

@Composable
fun OracleScreen(navController: NavHostController) {
    val vm: OracleViewModel = viewModel()
    val queries by vm.queries.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    val signedIn = Graph.sync.isSignedIn

    PalmScaffold(
        title = "Oracle",
        navController = navController,
        currentRoute = Routes.ORACLE,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !signedIn -> PalmEmptyState(
                        "Sign in (Settings) to consult the Oracle. Your questions are answered in the cloud using your own AI key.",
                    )
                    queries.isEmpty() -> PalmEmptyState(
                        "Ask the Oracle anything. Answers come back short, the way a Palm likes them.",
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                        items(queries, key = { it.id }) { rec ->
                            OracleEntry(rec)
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
            OracleComposer(enabled = signedIn, onAsk = vm::ask)
        }
    }
}

/** One question + its answer (or thinking / error state). */
@Composable
private fun OracleEntry(rec: RecordEntity) {
    PalmListCard {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = rec.body.orEmpty().trim().ifBlank { "(empty question)" },
                color = PalmInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            val answer = rec.aiResponse?.trim().orEmpty()
            when {
                rec.aiStatus == "done" && answer.isNotBlank() ->
                    Text(text = answer, color = PalmInk, fontSize = 14.sp)
                rec.aiStatus == "error" ->
                    Text(
                        text = "The Oracle could not answer this one. Ask again later.",
                        color = PalmRed,
                        fontSize = 13.sp,
                    )
                else ->
                    Text(
                        text = "Consulting the Oracle...",
                        color = PalmInkMute,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                    )
            }
        }
    }
}

/** Bottom composer: type a question, tap to ask. */
@Composable
private fun OracleComposer(enabled: Boolean, onAsk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(PalmLineSoft))
        PalmField(
            label = "Your question",
            value = text,
            onValueChange = { text = it },
            singleLine = false,
            minLines = 2,
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            OutlinedButton(
                onClick = {
                    onAsk(text)
                    text = ""
                },
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PalmTitleBar),
                border = BorderStroke(1.dp, PalmTitleBar),
            ) { Text("ask the oracle") }
        }
        Spacer(Modifier.height(10.dp))
        PalmDivider()
    }
}
