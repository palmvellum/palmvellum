package dev.tatliving.palmvellum.organizers.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import dev.tatliving.palmvellum.organizers.BuildConfig
import dev.tatliving.palmvellum.organizers.data.Clock
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.data.Ulid
import dev.tatliving.palmvellum.organizers.data.local.RecordEntity
import dev.tatliving.palmvellum.organizers.data.sync.SupabaseConfig
import dev.tatliving.palmvellum.organizers.ui.PalmScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmEmptyState
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.PalmListCard
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLine
import dev.tatliving.palmvellum.organizers.ui.theme.PalmOnDark
import dev.tatliving.palmvellum.organizers.ui.theme.PalmRed
import dev.tatliving.palmvellum.organizers.ui.theme.PalmTitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

// Palm Note Pad "paper" — the warm cream the original scribble pad used.
private val PaperBg = Color(0xFFF0EAD4)

/** Read one string field out of a record's metadata JSON. */
private fun metaString(metadataJson: String, key: String): String? =
    runCatching { Json.parseToJsonElement(metadataJson).jsonObject[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

/** Public-bucket URL for a sketch image (notepad bucket is public-read). */
private fun sketchUrl(metadataJson: String): String? {
    val path = metaString(metadataJson, "image_path") ?: return null
    return "${SupabaseConfig.URL}/storage/v1/object/public/notepad/$path"
}

private fun sketchTitle(r: RecordEntity): String =
    metaString(r.metadataJson, "palm_title")?.ifBlank { null } ?: "untitled"

class NotePadViewModel : ViewModel() {
    private val repo = Graph.repo
    val sketches = repo.observeRecords("sketch")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signedIn: Boolean get() = Graph.sync.isSignedIn

    /** Pull on entry so AI transcriptions (and other devices' sketches) appear. */
    fun refresh() = viewModelScope.launch { if (Graph.sync.isSignedIn) Graph.sync.syncNow() }

    fun delete(id: String) = viewModelScope.launch { repo.deleteRecord(id) }

    /** Rename: rewrite metadata.palm_title only (body stays the AI text). */
    fun rename(record: RecordEntity, title: String) = viewModelScope.launch {
        val meta = buildJsonObject {
            put("image_path", metaString(record.metadataJson, "image_path") ?: "")
            put("palm_title", title.trim().ifBlank { "untitled" })
            put("palm_modified_at", Clock.nowIso())
        }.toString()
        repo.saveRecord(record.copy(metadataJson = meta))
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    }

    /**
     * Render the strokes to a PNG, upload to the owner-only `notepad`
     * bucket, then create a pending `sketch` record. The process-sketch
     * Edge Function fires on INSERT, runs the vision model over the image,
     * and fills `body` with the transcription (arrives on a later pull).
     * `onResult(null)` = success, `onResult(msg)` = error.
     */
    fun saveSketch(
        strokes: List<FloatArray>,
        width: Int,
        height: Int,
        strokeWidthPx: Float,
        title: String,
        onResult: (String?) -> Unit,
    ) = viewModelScope.launch {
        val uid = Graph.session.userId
        if (uid.isNullOrBlank()) {
            onResult("Sign in (Settings) to save sketches for AI."); return@launch
        }
        if (width <= 0 || height <= 0 || strokes.isEmpty()) {
            onResult("Draw something first."); return@launch
        }
        try {
            val recordId = Ulid.new()
            val png = withContext(Dispatchers.Default) { renderSketchPng(strokes, width, height, strokeWidthPx) }
            val path = "$uid/$recordId.png"
            val up = Graph.sync.uploadObject("notepad", path, png, "image/png")
            if (up.isFailure) {
                onResult("Upload failed: ${up.exceptionOrNull()?.message ?: "unknown error"}"); return@launch
            }
            val now = Clock.nowIso()
            val metadata = buildJsonObject {
                put("image_path", path)
                put("palm_title", title.trim().ifBlank { "untitled" })
                put("palm_modified_at", now)
            }.toString()
            repo.saveRecord(
                RecordEntity(
                    id = recordId,
                    type = "sketch",
                    body = null,
                    metadataJson = metadata,
                    aiStatus = "pending",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (Graph.sync.isSignedIn) Graph.sync.syncNow()
            onResult(null)
        } catch (e: Exception) {
            onResult(e.message ?: "Save error")
        }
    }
}

/** Flatten strokes ([x0,y0,x1,y1,...] per stroke) onto a white PNG. */
private fun renderSketchPng(strokes: List<FloatArray>, w: Int, h: Int, strokeW: Float): ByteArray {
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    val dot = android.graphics.Paint(paint).apply { style = android.graphics.Paint.Style.FILL }
    strokes.forEach { s ->
        val n = s.size / 2
        when {
            n == 1 -> canvas.drawCircle(s[0], s[1], strokeW / 2f, dot)
            n > 1 -> {
                val p = android.graphics.Path()
                p.moveTo(s[0], s[1])
                for (i in 1 until n) p.lineTo(s[i * 2], s[i * 2 + 1])
                canvas.drawPath(p, paint)
            }
        }
    }
    val out = ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
}

@Composable
fun NotePadScreen(navController: NavHostController) {
    val vm: NotePadViewModel = viewModel()
    val sketches by vm.sketches.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    var drawing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<RecordEntity?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (drawing) {
        DrawSketch(
            navController = navController,
            saving = saving,
            error = error,
            onCancel = { drawing = false; error = null },
            onSave = { data, w, h, sw, title ->
                saving = true
                error = null
                vm.saveSketch(data, w, h, sw, title) { err ->
                    saving = false
                    if (err == null) drawing = false else error = err
                }
            },
        )
        return
    }

    // Keep the detail row in sync with the latest synced copy (AI body fills in).
    val live = detail?.let { d -> sketches.firstOrNull { it.id == d.id } ?: d }
    if (live != null) {
        SketchDetail(
            navController = navController,
            sketch = live,
            onBack = { detail = null },
            onDelete = { vm.delete(live.id); detail = null },
            onRename = { vm.rename(live, it) },
        )
        return
    }

    PalmScaffold(
        title = "Note Pad",
        navController = navController,
        currentRoute = Routes.NOTEPAD,
        titleAction = {
            TitleAction("+ draw") {
                if (vm.signedIn) { drawing = true; error = null }
                else error = "Sign in (Settings) to draw + AI-transcribe sketches."
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!vm.signedIn) {
                Text(
                    "Sign in (Settings) to draw sketches and have AI read your handwriting.",
                    color = PalmInkMute, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            error?.let { err ->
                Text(
                    "(!) $err",
                    color = PalmRed, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (sketches.isEmpty()) {
                PalmEmptyState("No sketches yet. Tap + draw to scribble a note — AI reads your handwriting.")
            } else {
                LazyVerticalGrid(
                    // Cosmo's wide display made the 2-up tiles huge; halve them to
                    // ~4 columns. Standard portrait keeps the classic two-up grid.
                    columns = if (BuildConfig.COSMO) GridCells.Adaptive(190.dp) else GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sketches, key = { it.id }) { s ->
                        SketchCard(s) { detail = s }
                    }
                }
            }
        }
    }
}

@Composable
private fun SketchCard(s: RecordEntity, onClick: () -> Unit) {
    val url = sketchUrl(s.metadataJson)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(dev.tatliving.palmvellum.organizers.ui.theme.PalmSurfaceLo)
            .border(1.dp, PalmLine)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(PaperBg),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = sketchTitle(s),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("no image", color = PalmInkMute, fontSize = 12.sp)
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(
                sketchTitle(s),
                color = PalmInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            SketchStatusLine(s, compact = true)
        }
    }
}

@Composable
private fun SketchStatusLine(s: RecordEntity, compact: Boolean) {
    when (s.aiStatus) {
        "pending", "processing", "queued" ->
            Text("[...] analyzing", color = Color(0xFFB8730A), fontSize = 12.sp)
        "error" ->
            Text("[err] AI could not read it", color = PalmRed, fontSize = 12.sp)
        else -> {
            val body = s.body.orEmpty().trim()
            if (body.isBlank()) {
                Text(if (compact) "tap to open" else "(blank)", color = PalmInkMute, fontSize = 12.sp)
            } else {
                Text(
                    body,
                    color = PalmInkMute, fontSize = 12.sp,
                    maxLines = if (compact) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SketchDetail(
    navController: NavHostController,
    sketch: RecordEntity,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    val url = sketchUrl(sketch.metadataJson)
    var renaming by remember(sketch.id) { mutableStateOf(false) }
    var titleDraft by remember(sketch.id) { mutableStateOf(sketchTitle(sketch)) }

    // Use the Palm frame (not the full-screen editor) so the four core buttons
    // stay visible while viewing a sketch.
    PalmScaffold(
        title = "Note Pad",
        navController = navController,
        currentRoute = Routes.NOTEPAD,
        titleAction = { TitleAction("done") { onBack() } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp)) {
            // Title + rename (only once AI is done, so we never clobber the body)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (renaming) {
                    Text(
                        sketchTitle(sketch),
                        color = PalmInk, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        sketchTitle(sketch),
                        color = PalmInk, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (sketch.aiStatus == "done" || sketch.aiStatus == null) {
                        Text(
                            "rename",
                            color = PalmTitleBar, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { renaming = true }.padding(6.dp),
                        )
                    }
                }
            }
            if (renaming) {
                PalmField("Title", titleDraft, { titleDraft = it })
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(
                        "save",
                        color = PalmTitleBar, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onRename(titleDraft); renaming = false }.padding(6.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "cancel",
                        color = PalmInkMute, fontSize = 14.sp,
                        modifier = Modifier.clickable { renaming = false; titleDraft = sketchTitle(sketch) }.padding(6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Image
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(PaperBg).border(1.dp, PalmLine),
                contentAlignment = Alignment.Center,
            ) {
                if (url != null) {
                    AsyncImage(
                        model = url,
                        contentDescription = sketchTitle(sketch),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                } else {
                    Text("no image", color = PalmInkMute, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            // AI transcription
            Text(
                "AI TRANSCRIPTION",
                color = PalmTitleBar, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            PalmListCard {
                Column(Modifier.padding(12.dp)) {
                    when (sketch.aiStatus) {
                        "pending", "processing", "queued" ->
                            Text("[...] vision model is reading your sketch", color = Color(0xFFB8730A), fontSize = 14.sp)
                        "error" ->
                            Text("[err] AI could not read this sketch.", color = PalmRed, fontSize = 14.sp)
                        else -> Text(
                            sketch.body?.takeIf { it.isNotBlank() } ?: "(blank)",
                            color = PalmInk, fontSize = 15.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            DeleteButton(onDelete)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DrawSketch(
    navController: NavHostController,
    saving: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onSave: (List<FloatArray>, Int, Int, Float, String) -> Unit,
) {
    val strokeWidthPx = with(LocalDensity.current) { 3.dp.toPx() }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var title by remember { mutableStateOf("") }
    val hasInk = strokes.isNotEmpty() || current.isNotEmpty()
    val canSave = hasInk && !saving
    val doSave: () -> Unit = {
        val all = strokes.toList() + if (current.isNotEmpty()) listOf(current) else emptyList()
        val data = all.map { pts ->
            FloatArray(pts.size * 2) { i -> if (i % 2 == 0) pts[i / 2].x else pts[i / 2].y }
        }
        onSave(data, canvasSize.width, canvasSize.height, strokeWidthPx, title)
    }

    // Use the Palm frame (not the full-screen editor) so the four core buttons
    // stay visible while drawing. Cancel / save live in the title bar.
    PalmScaffold(
        title = "New Note",
        navController = navController,
        currentRoute = Routes.NOTEPAD,
        wide = true,
        titleAction = {
            TitleAction("cancel") { onCancel() }
            Text(
                "save",
                color = if (canSave) PalmOnDark else Color(0x66FFFFFF),
                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(enabled = canSave, onClick = doSave)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        },
    ) { padding ->
        // Two-pane: text input on the left, a square grid drawing area on the right.
        Row(Modifier.fillMaxSize().padding(padding)) {
            // Left column — text input + drawing controls.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                error?.let { err ->
                    Text(
                        "(!) $err",
                        color = PalmRed, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                PalmField("Title (optional)", title, { title = it })
                Spacer(Modifier.height(8.dp))
                // Toolbar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "undo",
                        color = if (hasInk) PalmTitleBar else PalmInkMute,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable(enabled = hasInk) {
                                if (current.isNotEmpty()) current = emptyList()
                                else if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                            }
                            .padding(6.dp),
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        "clear",
                        color = if (hasInk) PalmRed else PalmInkMute,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable(enabled = hasInk) { strokes.clear(); current = emptyList() }
                            .padding(6.dp),
                    )
                }
                if (saving) {
                    Spacer(Modifier.height(8.dp))
                    Text("saving...", color = PalmInkMute, fontSize = 13.sp)
                }
            }
            // Right column — square graph-paper drawing surface.
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val side = if (maxWidth < maxHeight) maxWidth else maxHeight
                Box(
                    modifier = Modifier
                        .size(side)
                        .background(PaperBg)
                        .border(1.dp, PalmLine)
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset -> current = listOf(offset) },
                                onDrag = { change, _ ->
                                    current = current + change.position
                                    change.consume()
                                },
                                onDragEnd = {
                                    if (current.isNotEmpty()) { strokes.add(current); current = emptyList() }
                                },
                                onDragCancel = {
                                    if (current.isNotEmpty()) { strokes.add(current); current = emptyList() }
                                },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        // Faint graph-paper grid (square cells).
                        val cell = size.minDimension / 12f
                        val gridColor = Color(0x22000000)
                        var gx = cell
                        while (gx < size.width) {
                            drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), 1f)
                            gx += cell
                        }
                        var gy = cell
                        while (gy < size.height) {
                            drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), 1f)
                            gy += cell
                        }
                        val all = strokes + listOf(current)
                        all.forEach { pts ->
                            when {
                                pts.size == 1 -> drawCircle(Color.Black, strokeWidthPx / 2f, pts[0])
                                pts.size > 1 -> {
                                    val path = Path().apply {
                                        moveTo(pts[0].x, pts[0].y)
                                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                                    }
                                    drawPath(
                                        path,
                                        Color.Black,
                                        style = Stroke(
                                            width = strokeWidthPx,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
