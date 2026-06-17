package dev.tatliving.palmvellum.organizers.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.BuildConfig
import dev.tatliving.palmvellum.organizers.data.CalRefreshWorker
import dev.tatliving.palmvellum.organizers.data.CalSub
import dev.tatliving.palmvellum.organizers.data.CalSubStore
import dev.tatliving.palmvellum.organizers.data.CalendarSync
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.data.IcsImport
import dev.tatliving.palmvellum.organizers.data.sync.SyncStatus
import dev.tatliving.palmvellum.organizers.ui.PalmScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmCategoryStrip
import dev.tatliving.palmvellum.organizers.ui.components.PalmDivider
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLine
import dev.tatliving.palmvellum.organizers.ui.theme.PalmRed
import dev.tatliving.palmvellum.organizers.ui.theme.PalmTitleBar
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    private val sync = Graph.sync

    var email by mutableStateOf(sync.email ?: "")
    var code by mutableStateOf("")
    var codeSent by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val signedIn: Boolean get() = sync.isSignedIn
    val signedInEmail: String? get() = sync.email
    val status: SyncStatus get() = sync.status
    val syncError: String? get() = sync.lastError

    fun sendCode() {
        busy = true; error = null
        viewModelScope.launch {
            val r = sync.sendOtp(email)
            busy = false
            if (r.isSuccess) codeSent = true else error = r.exceptionOrNull()?.message
        }
    }

    fun verify() {
        busy = true; error = null
        viewModelScope.launch {
            val r = sync.verifyOtp(email, code)
            busy = false
            if (r.isSuccess) { codeSent = false; code = "" } else error = r.exceptionOrNull()?.message
        }
    }

    fun syncNow() = viewModelScope.launch { sync.syncNow() }
    fun signOut() = sync.signOut()
}

@Composable
fun SettingsScreen(navController: NavHostController) {
    val vm: SettingsViewModel = viewModel()

    PalmScaffold(
        title = "Settings",
        navController = navController,
        currentRoute = Routes.SETTINGS,
        // Cosmo: use the full landscape width for the two-pane layout.
        wide = BuildConfig.COSMO,
        titleAction = { TitleAction("home") { navController.navigate(Routes.LAUNCHER) { popUpTo(Routes.LAUNCHER) { inclusive = true } } } },
    ) { padding ->
        if (BuildConfig.COSMO) {
            // Two panes: cloud sync on the left, calendars on the right.
            Row(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    CloudSyncSection(vm)
                    Spacer(Modifier.height(24.dp))
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(PalmLine))
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    CalendarSubscriptionsSection()
                    Spacer(Modifier.height(24.dp))
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(top = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CloudSyncSection(vm)
                Spacer(Modifier.height(20.dp))
                PalmDivider()
                Spacer(Modifier.height(12.dp))
                CalendarSubscriptionsSection()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Cloud sync sign-in / status section. */
@Composable
private fun CloudSyncSection(vm: SettingsViewModel) {
    Text(
        "Cloud sync",
        color = PalmInk,
        fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
    )
    Text(
        "PalmVellum works fully on this device with no account. Sign in to back up and sync across devices.",
        color = PalmInkMute,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    Spacer(Modifier.height(12.dp))

    if (vm.signedIn) {
        Text(
            "Signed in as ${vm.signedInEmail ?: "(unknown)"}",
            color = PalmInk,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(6.dp))
        val statusText = when (vm.status) {
            SyncStatus.SYNCING -> "Syncing..."
            SyncStatus.SUCCESS -> "Synced"
            SyncStatus.ERROR -> "Sync error: ${vm.syncError ?: ""}"
            SyncStatus.IDLE -> "Idle"
        }
        Text(statusText, color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Button(
                onClick = { vm.syncNow() },
                enabled = vm.status != SyncStatus.SYNCING,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
            ) { Text("Sync now") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.signOut() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PalmRed),
            ) { Text("Sign out", color = PalmRed) }
        }
    } else if (!vm.codeSent) {
        PalmField("Email", vm.email, { vm.email = it }, keyboardType = KeyboardType.Email)
        vm.error?.let { Text(it, color = PalmRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
        Column(Modifier.padding(horizontal = 14.dp)) {
            Button(
                onClick = { vm.sendCode() },
                enabled = !vm.busy && vm.email.contains("@"),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
            ) { Text(if (vm.busy) "Sending..." else "Send code") }
        }
    } else {
        Text(
            "Code sent to ${vm.email}. Enter the 6-digit code.",
            color = PalmInk,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        PalmField("Code", vm.code, { vm.code = it }, keyboardType = KeyboardType.Number)
        vm.error?.let { Text(it, color = PalmRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
        Column(Modifier.padding(horizontal = 14.dp)) {
            Button(
                onClick = { vm.verify() },
                enabled = !vm.busy && vm.code.length >= 6,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
            ) { Text(if (vm.busy) "Verifying..." else "Sign in") }
            Spacer(Modifier.height(8.dp))
            Text(
                "back / re-send",
                color = PalmInkMute,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
                    .clickable { vm.codeSent = false; vm.error = null },
            )
        }
    }
}

/** Manage read-only iCal calendar subscriptions (e.g. a Google Calendar feed). */
@Composable
private fun CalendarSubscriptionsSection() {
    val context = LocalContext.current
    val store = remember { CalSubStore(context) }
    var subs by remember { mutableStateOf(store.list()) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var interval by remember { mutableStateOf(store.intervalHours()) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val icsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text == null) {
            importMsg = "Could not read that file."
        } else {
            scope.launch { importMsg = "Imported ${IcsImport.importText(text)} event(s)." }
        }
    }

    Text(
        "Calendar subscriptions",
        color = PalmInk, fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
    )
    Text(
        "Read-only iCal feeds — e.g. a Google Calendar's \"Secret address in iCal format\". Events are pulled in and synced. Tap Refresh after adding.",
        color = PalmInkMute, fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    Spacer(Modifier.height(8.dp))

    subs.forEach { sub ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(sub.name.ifBlank { sub.url }, color = PalmInk, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub.url, color = PalmInkMute, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "remove",
                color = PalmRed, fontSize = 13.sp,
                modifier = Modifier.clickable { store.remove(sub.url); subs = store.list() }.padding(6.dp),
            )
        }
    }

    PalmField("Name", name, { name = it })
    PalmField("iCal URL", url, { url = it })
    msg?.let {
        Text(it, color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }
    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Button(
            onClick = {
                if (url.isNotBlank()) {
                    store.add(CalSub(name.trim(), url.trim()))
                    subs = store.list(); name = ""; url = ""; msg = null
                }
            },
            enabled = url.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
        ) { Text("Add") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true; msg = null
                scope.launch {
                    val r = CalendarSync.refresh(context)
                    busy = false
                    msg = r.fold({ "Refreshed: $it event(s) updated." }, { "Error: ${it.message}" })
                }
            },
        ) { Text(if (busy) "Refreshing..." else "Refresh now") }
    }

    Spacer(Modifier.height(8.dp))
    Text("Auto-update", color = PalmInk, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    PalmCategoryStrip(
        options = listOf("0" to "off", "6" to "every 6h", "12" to "every 12h", "24" to "daily"),
        selected = interval.toString(),
        onSelect = { sel ->
            interval = sel.toIntOrNull() ?: 0
            store.setIntervalHours(interval)
            CalRefreshWorker.schedule(context)
        },
    )

    Spacer(Modifier.height(12.dp))
    PalmDivider()
    Spacer(Modifier.height(8.dp))
    Text("Import calendar file", color = PalmInk, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    Text(
        "Load events from a .ics file into your Date Book.",
        color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp),
    )
    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        OutlinedButton(
            onClick = {
                importMsg = null
                icsPicker.launch(arrayOf("text/calendar", "application/octet-stream", "*/*"))
            },
        ) { Text("Import .ics file") }
    }
    importMsg?.let {
        Text(it, color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }
}
