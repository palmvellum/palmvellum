package dev.tatliving.palmvellum.organizers.ui.screens

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import dev.tatliving.palmvellum.organizers.ui.MasterDetailScaffold
import dev.tatliving.palmvellum.organizers.ui.components.EditorScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmDivider
import dev.tatliving.palmvellum.organizers.ui.components.PalmEmptyState
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.PalmListCard
import dev.tatliving.palmvellum.organizers.ui.components.PalmRow
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A body starting with "(ai)" routes the row to the server-side AI agent
 *  (ai-agent Edge Function, using the user's BYOK key from Vault). */
fun isAiRequest(body: String?): Boolean =
    body?.trimStart()?.startsWith("(ai)", ignoreCase = true) == true

/** PDF / DOCX / image — the file types the server summarizer accepts. */
val UPLOAD_MIME_FILTER = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "image/*",
)
private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024 // 20 MB (matches bucket limit)

class MemoViewModel : ViewModel() {
    private val repo = Graph.repo
    val memos = repo.observeRecords("thought")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(r: RecordEntity) = viewModelScope.launch {
        val withAi = if (isAiRequest(r.body)) r.copy(aiStatus = "pending") else r
        repo.saveRecord(withAi)
        // Push immediately so the server agent can start; result returns on a later pull.
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    }

    fun delete(id: String) = viewModelScope.launch { repo.deleteRecord(id) }

    /** Pull on screen entry so AI results (and other devices' edits) show up. */
    fun refresh() = viewModelScope.launch { if (Graph.sync.isSignedIn) Graph.sync.syncNow() }

    /**
     * Drop a PDF / DOCX / image into the Memo Pad: upload the bytes to the
     * private `memo-uploads` bucket, then create a pending `thought` record
     * carrying `metadata.upload_path`. The server webhook reads the file and
     * fills the memo body in with an AI summary (result arrives on a pull).
     * `onResult(null)` = success, `onResult(msg)` = error.
     */
    fun uploadFile(resolver: ContentResolver, uri: Uri, onResult: (String?) -> Unit) = viewModelScope.launch {
        val uid = Graph.session.userId
        if (uid.isNullOrBlank()) {
            onResult("Sign in (Settings) to let AI read files."); return@launch
        }
        try {
            val meta = withContext(Dispatchers.IO) { readUpload(resolver, uri) }
            if (meta.bytes.isEmpty()) { onResult("Couldn't read that file."); return@launch }
            if (meta.bytes.size > MAX_UPLOAD_BYTES) {
                onResult("${meta.filename} is too large (max 20 MB)."); return@launch
            }
            val ext = extFor(meta.filename, meta.mime)
            if (!isAcceptedUpload(meta.mime, ext)) {
                onResult("${meta.filename} — only PDF / DOCX / image accepted."); return@launch
            }
            val recordId = Ulid.new()
            val path = "$uid/$recordId.$ext"
            val up = Graph.sync.uploadObject("memo-uploads", path, meta.bytes, meta.mime)
            if (up.isFailure) {
                onResult("Upload failed: ${up.exceptionOrNull()?.message ?: "unknown error"}"); return@launch
            }
            val now = Clock.nowIso()
            val metadata = buildJsonObject {
                put("palm_category_name", "Uploads")
                put("upload_path", path)
                put("upload_filename", meta.filename)
                put("upload_mimetype", meta.mime)
                put("upload_size", meta.bytes.size)
            }.toString()
            repo.saveRecord(
                RecordEntity(
                    id = recordId,
                    type = "thought",
                    body = "(FILE) ${meta.filename}\n\nAI is reading the file. This memo will fill in shortly.",
                    metadataJson = metadata,
                    aiStatus = "pending",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (Graph.sync.isSignedIn) Graph.sync.syncNow()
            onResult(null)
        } catch (e: Exception) {
            onResult(e.message ?: "Upload error")
        }
    }
}

private data class UploadMeta(val filename: String, val mime: String, val bytes: ByteArray)

private fun readUpload(resolver: ContentResolver, uri: Uri): UploadMeta {
    var name = "file"
    resolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
    }
    val mime = resolver.getType(uri).orEmpty()
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    return UploadMeta(name, mime, bytes)
}

private fun extFor(filename: String, mime: String): String {
    val dot = filename.substringAfterLast('.', "")
    if (dot.isNotBlank() && dot.length <= 5) return dot.lowercase()
    return when {
        mime == "application/pdf" -> "pdf"
        mime.contains("wordprocessingml") -> "docx"
        mime.startsWith("image/") -> mime.substringAfter('/').substringBefore('+').ifBlank { "img" }
        else -> "bin"
    }
}

private fun isAcceptedUpload(mime: String, ext: String): Boolean =
    mime.startsWith("image/") ||
        mime == "application/pdf" ||
        mime.contains("wordprocessingml") ||
        ext in setOf("pdf", "docx", "png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp")

@Composable
fun MemoScreen(navController: NavHostController) {
    val vm: MemoViewModel = viewModel()
    val memos by vm.memos.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    var editing by remember { mutableStateOf<RecordEntity?>(null) }

    val resolver = LocalContext.current.contentResolver
    var uploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploadError = null
        uploading = true
        vm.uploadFile(resolver, uri) { err ->
            uploading = false
            uploadError = err
        }
    }

    MasterDetailScaffold(
        title = "Memo Pad",
        navController = navController,
        currentRoute = Routes.MEMO,
        detail = editing,
        titleAction = {
            TitleAction(if (uploading) "uploading..." else "+ file") {
                if (!uploading) picker.launch(UPLOAD_MIME_FILTER)
            }
            TitleAction("+ new") { editing = newMemo() }
        },
        placeholder = "Pick a memo from the list, or tap + new.",
        master = {
            Column(modifier = Modifier.fillMaxSize()) {
                uploadError?.let { err ->
                    Text(
                        text = "(!) $err",
                        color = PalmRed,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (memos.isEmpty()) {
                    PalmEmptyState("No memos. Tap + new to jot one down, or + file to have AI read a PDF / DOCX / image.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                        item {
                            PalmListCard {
                                memos.forEachIndexed { i, rec ->
                                    if (i > 0) PalmDivider()
                                    val text = rec.body.orEmpty().trim()
                                    val firstLine = text.lineSequence().firstOrNull().orEmpty()
                                    val rest = text.removePrefix(firstLine).trim()
                                    val aiMeta = when (rec.aiStatus) {
                                        "pending", "processing", "queued" -> "AI thinking..."
                                        else -> null
                                    }
                                    PalmRow(
                                        title = firstLine.ifBlank { "(empty memo)" },
                                        meta = aiMeta,
                                        body = rest.ifBlank { null },
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
            MemoEditor(
                initial = target,
                isNew = target.id.isEmpty(),
                embedded = embedded,
                onCancel = { editing = null },
                onSave = { vm.save(it); editing = null },
                onDelete = { vm.delete(target.id); editing = null },
            )
        },
    )
}

private fun newMemo(): RecordEntity {
    val now = Clock.nowIso()
    return RecordEntity(id = "", type = "thought", body = "", createdAt = now, updatedAt = now)
}

@Composable
private fun MemoEditor(
    initial: RecordEntity,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (RecordEntity) -> Unit,
    onDelete: () -> Unit,
    embedded: Boolean = false,
) {
    var text by remember { mutableStateOf(initial.body ?: "") }

    EditorScaffold(
        title = if (isNew) "New Memo" else "Edit Memo",
        onCancel = onCancel,
        embedded = embedded,
        saveEnabled = text.isNotBlank(),
        onSave = {
            onSave(initial.copy(id = initial.id.ifEmpty { Ulid.new() }, body = text.trim()))
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PalmField("Memo  (start with \"(ai)\" to ask the agent)", text, { text = it }, singleLine = false, minLines = 8)
            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                DeleteButton(onDelete)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
