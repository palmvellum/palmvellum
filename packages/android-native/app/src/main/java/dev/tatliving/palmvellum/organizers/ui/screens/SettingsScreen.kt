package dev.tatliving.palmvellum.organizers.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.collectAsState
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
import dev.tatliving.palmvellum.organizers.data.CalSubStore
import dev.tatliving.palmvellum.organizers.data.CalSubs
import dev.tatliving.palmvellum.organizers.data.CalendarSync
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.data.IcsImport
import dev.tatliving.palmvellum.organizers.data.sync.SyncStatus
import dev.tatliving.palmvellum.organizers.ui.PalmScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmCategoryStrip
import dev.tatliving.palmvellum.organizers.ui.components.PalmDivider
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.i18n.I18n
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLine
import dev.tatliving.palmvellum.organizers.ui.theme.PalmRed
import dev.tatliving.palmvellum.organizers.ui.theme.PalmTitleBar
import kotlinx.coroutines.launch

/** The web app's settings page — AI provider keys (BYOK) and credits live here. */
private const val WEB_SETTINGS_URL = "https://tatliving.dev/palmvellum/app/settings"

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
        title = I18n.t("settings.title"),
        navController = navController,
        currentRoute = Routes.SETTINGS,
        // Cosmo: use the full landscape width for the two-pane layout.
        wide = BuildConfig.COSMO,
        titleAction = { TitleAction(I18n.t("common.home")) { navController.navigate(Routes.LAUNCHER) { popUpTo(Routes.LAUNCHER) { inclusive = true } } } },
    ) { padding ->
        if (BuildConfig.COSMO) {
            // Two panes: account + AI + language on the left, calendars on the right.
            Row(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    CloudSyncSection(vm)
                    Spacer(Modifier.height(20.dp))
                    PalmDivider()
                    Spacer(Modifier.height(12.dp))
                    AccountAiSection()
                    Spacer(Modifier.height(20.dp))
                    PalmDivider()
                    Spacer(Modifier.height(12.dp))
                    LanguageSection()
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
                AccountAiSection()
                Spacer(Modifier.height(20.dp))
                PalmDivider()
                Spacer(Modifier.height(12.dp))
                LanguageSection()
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
        I18n.t("settings.cloudSync"),
        color = PalmInk,
        fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
    )
    Text(
        I18n.t("settings.cloudSyncSub"),
        color = PalmInkMute,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    Spacer(Modifier.height(12.dp))

    if (vm.signedIn) {
        Text(
            I18n.t("settings.signedInAs", vm.signedInEmail ?: I18n.t("settings.unknown")),
            color = PalmInk,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(6.dp))
        val statusText = when (vm.status) {
            SyncStatus.SYNCING -> I18n.t("settings.status.syncing")
            SyncStatus.SUCCESS -> I18n.t("settings.status.synced")
            SyncStatus.ERROR -> I18n.t("settings.status.error", vm.syncError ?: "")
            SyncStatus.IDLE -> I18n.t("settings.status.idle")
        }
        Text(statusText, color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Button(
                onClick = { vm.syncNow() },
                enabled = vm.status != SyncStatus.SYNCING,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
            ) { Text(I18n.t("settings.syncNow")) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.signOut() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PalmRed),
            ) { Text(I18n.t("settings.signOut"), color = PalmRed) }
        }
    } else if (!vm.codeSent) {
        PalmField(I18n.t("settings.email"), vm.email, { vm.email = it }, keyboardType = KeyboardType.Email)
        vm.error?.let { Text(it, color = PalmRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
        Column(Modifier.padding(horizontal = 14.dp)) {
            Button(
                onClick = { vm.sendCode() },
                enabled = !vm.busy && vm.email.contains("@"),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
            ) { Text(if (vm.busy) I18n.t("settings.sending") else I18n.t("settings.sendCode")) }
        }
    } else {
        Text(
            I18n.t("settings.codeSent", vm.email),
            color = PalmInk,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        PalmField(I18n.t("settings.code"), vm.code, { vm.code = it }, keyboardType = KeyboardType.Number)
        vm.error?.let { Text(it, color = PalmRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
        Column(Modifier.padding(horizontal = 14.dp)) {
            Button(
                onClick = { vm.verify() },
                enabled = !vm.busy && vm.code.length >= 6,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
            ) { Text(if (vm.busy) I18n.t("settings.verifying") else I18n.t("settings.signIn")) }
            Spacer(Modifier.height(8.dp))
            Text(
                I18n.t("settings.backResend"),
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

/**
 * AI provider keys and platform credits both live in the web app. These rows
 * open the web settings page (https://tatliving.dev/palmvellum/app/settings) in
 * the browser, signed in to the same account.
 */
@Composable
private fun AccountAiSection() {
    val context = LocalContext.current
    val openWeb = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEB_SETTINGS_URL)))
        }
    }

    Text(
        I18n.t("settings.account"),
        color = PalmInk, fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
    )
    Text(
        I18n.t("settings.accountSub"),
        color = PalmInkMute, fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    Spacer(Modifier.height(8.dp))
    LinkRow(I18n.t("settings.aiSettings"), I18n.t("settings.aiSettingsSub")) { openWeb() }
    PalmDivider()
    LinkRow(I18n.t("settings.topup"), I18n.t("settings.topupSub")) { openWeb() }
}

/** A tappable settings row: title + subtitle, with a trailing chevron. */
@Composable
private fun LinkRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PalmInk, fontSize = 15.sp)
            Text(sub, color = PalmInkMute, fontSize = 12.sp)
        }
        Text("›", color = PalmInkMute, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** App language picker — the same six locales the web app ships. */
@Composable
private fun LanguageSection() {
    Text(
        I18n.t("settings.language"),
        color = PalmInk, fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
    )
    Text(
        I18n.t("settings.languageSub"),
        color = PalmInkMute, fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    Spacer(Modifier.height(8.dp))
    PalmCategoryStrip(
        options = I18n.locales,
        selected = I18n.locale,
        onSelect = { I18n.setLanguage(it) },
    )
}

/** Manage read-only iCal calendar subscriptions (e.g. a Google Calendar feed). */
@Composable
private fun CalendarSubscriptionsSection() {
    val context = LocalContext.current
    val store = remember { CalSubStore(context) }
    // The subscription list is a synced record now, so it streams in (and
    // reflects feeds added on the web or another device).
    val subs by CalSubs.observe().collectAsState(initial = emptyList())
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
            importMsg = I18n.t("settings.importErr")
        } else {
            scope.launch { importMsg = I18n.t("settings.importOk", IcsImport.importText(text)) }
        }
    }

    Text(
        I18n.t("settings.calSubs"),
        color = PalmInk, fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
    )
    Text(
        I18n.t("settings.calSubsSub"),
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
                I18n.t("common.remove"),
                color = PalmRed, fontSize = 13.sp,
                modifier = Modifier.clickable { scope.launch { CalSubs.remove(sub.url) } }.padding(6.dp),
            )
        }
    }

    PalmField(I18n.t("settings.name"), name, { name = it })
    PalmField(I18n.t("settings.icalUrl"), url, { url = it })
    msg?.let {
        Text(it, color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }
    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Button(
            onClick = {
                if (url.isNotBlank()) {
                    val n = name.trim(); val u = url.trim()
                    scope.launch { CalSubs.add(n.ifBlank { u }, u) }
                    name = ""; url = ""; msg = null
                }
            },
            enabled = url.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = PalmTitleBar),
        ) { Text(I18n.t("common.add")) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true; msg = null
                scope.launch {
                    val r = CalendarSync.refresh(context)
                    busy = false
                    msg = r.fold({ I18n.t("settings.refreshed", it) }, { I18n.t("settings.refreshError", it.message ?: "") })
                }
            },
        ) { Text(if (busy) I18n.t("settings.refreshing") else I18n.t("settings.refreshNow")) }
    }

    Spacer(Modifier.height(8.dp))
    Text(I18n.t("settings.autoUpdate"), color = PalmInk, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    PalmCategoryStrip(
        options = listOf(
            "0" to I18n.t("settings.auto.off"),
            "6" to I18n.t("settings.auto.6"),
            "12" to I18n.t("settings.auto.12"),
            "24" to I18n.t("settings.auto.24"),
        ),
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
    Text(I18n.t("settings.importHeading"), color = PalmInk, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    Text(
        I18n.t("settings.importSub"),
        color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp),
    )
    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        OutlinedButton(
            onClick = {
                importMsg = null
                icsPicker.launch(arrayOf("text/calendar", "application/octet-stream", "*/*"))
            },
        ) { Text(I18n.t("settings.importBtn")) }
    }
    importMsg?.let {
        Text(it, color = PalmInkMute, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }
}
