package dev.tatliving.palmvellum.organizers.ui.screens

import android.content.ContentResolver
import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.BuildConfig
import dev.tatliving.palmvellum.organizers.data.Clock
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.data.Ulid
import dev.tatliving.palmvellum.organizers.data.local.RecordEntity
import dev.tatliving.palmvellum.organizers.data.hotsync.HotSyncConduit
import dev.tatliving.palmvellum.organizers.data.hotsync.HotSyncException
import dev.tatliving.palmvellum.organizers.data.hotsync.HotSyncSession
import dev.tatliving.palmvellum.organizers.data.hotsync.PalmCloud
import dev.tatliving.palmvellum.organizers.data.hotsync.PalmUsbTransport
import dev.tatliving.palmvellum.organizers.data.hotsync.UsbPermission
import dev.tatliving.palmvellum.organizers.ui.PalmScaffold
import dev.tatliving.palmvellum.organizers.ui.i18n.I18n
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLine
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLineSoft
import dev.tatliving.palmvellum.organizers.ui.theme.PalmOnDark
import dev.tatliving.palmvellum.organizers.ui.theme.PalmSurfaceLo
import dev.tatliving.palmvellum.organizers.ui.theme.PalmTitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * USB HotSync — connect a vintage Palm/CLIE to the Cosmo's USB-C port and sync
 * its Memo Pad + To Do databases with the PalmVellum cloud, no desktop needed.
 *
 * Reached only from the Cosmo launcher (the standard portrait build hides the
 * tile), but the screen is harmless on any device with USB host support.
 */
@Composable
fun HotSyncScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = remember { mutableListOf<String>().toMutableStateList() }
    var running by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // .prc/.pdb files queued to install onto the Palm during the next sync.
    val installs = remember { mutableStateListOf<InstallItem>() }

    // Keep the log scrolled to the newest line.
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    fun line(s: String) { log.add(s) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val items = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { readFile(context.contentResolver, it) }.getOrNull() } }
            installs.addAll(items)
        }
    }

    PalmScaffold(
        title = I18n.t("hotsync.title"),
        navController = navController,
        currentRoute = Routes.HOTSYNC,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PalmSurfaceLo)
                    .border(1.dp, PalmLineSoft)
                    .padding(12.dp),
            ) {
                Text(I18n.t("hotsync.howto.title"), fontWeight = FontWeight.Bold, color = PalmInk, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(I18n.t("hotsync.howto.body"), color = PalmInkMute, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("v${BuildConfig.VERSION_NAME}", color = PalmInkMute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.height(12.dp))

            val signedIn = Graph.sync.isSignedIn
            if (!signedIn) {
                Text(I18n.t("hotsync.needsignin"), color = PalmInkMute, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
            }

            // Start button (no dedicated PalmButton in this codebase — style a row).
            val enabled = signedIn && !running
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (enabled) PalmTitleBar else PalmLineSoft)
                    .clickable(enabled = enabled) {
                        running = true
                        log.clear()
                        val toInstall = installs.toList()
                        scope.launch {
                            // SnapshotStateList is safe to mutate off the main thread.
                            withContext(Dispatchers.IO) { runHotSync(context, toInstall) { msg -> line(msg) } }
                            installs.clear()
                            running = false
                        }
                    }
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (running) I18n.t("hotsync.syncing") else I18n.t("hotsync.start"),
                    color = PalmOnDark,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Queue .prc/.pdb files to install onto the Palm on the next Start.
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PalmSurfaceLo)
                    .border(1.dp, PalmLine)
                    .clickable(enabled = !running) { picker.launch(arrayOf("*/*")) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(I18n.t("hotsync.install"), color = PalmInk, fontWeight = FontWeight.Bold)
            }
            if (installs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    I18n.t("hotsync.installqueued", installs.joinToString(", ") { it.name }),
                    color = PalmInkMute,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    I18n.t("hotsync.installclear"),
                    color = PalmInkMute,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = !running) { installs.clear() },
                )
            }

            // Save the on-screen log to a Memo (which syncs to the cloud) so it
            // can be copied — the log view itself isn't selectable on-device.
            if (log.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val canSave = signedIn && !running
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PalmSurfaceLo)
                        .border(1.dp, PalmLine)
                        .clickable(enabled = canSave) {
                            scope.launch {
                                val text = log.joinToString("\n")
                                val err = withContext(Dispatchers.IO) { saveLogToMemo(text) }
                                line(
                                    if (err == null) I18n.t("hotsync.savedlog")
                                    else "${I18n.t("hotsync.savelogfail")} $err",
                                )
                            }
                        }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(I18n.t("hotsync.savelog"), color = PalmInk, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Live sync log.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(PalmSurfaceLo)
                    .border(1.dp, PalmLine)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(log) { l ->
                    Text(l, color = PalmInk, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/**
 * Persist the HotSync log as a Memo Pad record so it syncs to the cloud and can
 * be opened/copied on the web app or another device. Returns null on success or
 * an error message. Call off the main thread.
 */
private suspend fun saveLogToMemo(logText: String): String? = try {
    val now = Clock.nowIso()
    val header = "HotSync log — PalmVellum v${BuildConfig.VERSION_NAME}"
    Graph.repo.saveRecord(
        RecordEntity(
            id = Ulid.new(),
            userId = Graph.session.userId,
            type = "thought",
            body = "$header\n\n$logText",
            createdAt = now,
            updatedAt = now,
        ),
    )
    if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    null
} catch (e: Exception) {
    e.message ?: e.toString()
}

/** A .prc/.pdb file the user queued to install onto the Palm. */
private class InstallItem(val name: String, val bytes: ByteArray)

/** Read a picked document's display name + bytes. */
private fun readFile(resolver: ContentResolver, uri: Uri): InstallItem {
    var name = "file"
    resolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
    }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    return InstallItem(name, bytes)
}

/** Run one full HotSync, streaming progress to [log]. Call off the main thread. */
private suspend fun runHotSync(context: Context, installs: List<InstallItem> = emptyList(), log: (String) -> Unit) {
    val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    if (manager == null) { log(I18n.t("hotsync.nousb")); return }

    val device = PalmUsbTransport.findPalmDevice(manager)
    if (device == null) {
        log(I18n.t("hotsync.nodevice"))
        return
    }
    log(I18n.t("hotsync.found") + " ${device.productName ?: device.deviceName}")

    if (!UsbPermission.ensure(context, manager, device)) {
        log(I18n.t("hotsync.nopermission"))
        return
    }

    val uid = Graph.session.userId
    if (uid.isNullOrBlank()) { log(I18n.t("hotsync.needsignin")); return }

    val opened = try {
        PalmUsbTransport.open(manager, device, log)
    } catch (e: HotSyncException) {
        log("Error: ${e.message}")
        return
    }
    log("Device ${opened.label}, ${opened.stack} protocol")

    val session = HotSyncSession(opened.transport, opened.stack)
    try {
        log(I18n.t("hotsync.handshake"))
        session.open()
        val cloud = PalmCloud(Graph.rest, uid)
        HotSyncConduit(cloud).run(session, log)
        // Install any queued .prc/.pdb files (best-effort, one failure per file).
        for (item in installs) {
            try {
                val name = session.installFile(item.bytes, log)
                log("Installed $name")
            } catch (e: Exception) {
                log("Install ${item.name} failed — ${e.message ?: e.toString()}")
            }
        }
        log(I18n.t("hotsync.done"))
    } catch (e: Exception) {
        log("Error: ${e.message ?: e.toString()}")
    } finally {
        // Always close the session cleanly so the Palm completes its HotSync
        // and returns to the launcher instead of hanging on "waiting".
        runCatching {
            session.log("PalmVellum sync complete")
            session.finish()
        }
        opened.transport.close()
    }
}
