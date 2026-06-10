package dev.tatliving.palmvellum.organizers.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.data.sync.SyncStatus
import dev.tatliving.palmvellum.organizers.ui.PalmScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
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
        titleAction = { TitleAction("home") { navController.navigate(Routes.LAUNCHER) { popUpTo(Routes.LAUNCHER) { inclusive = true } } } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(top = 8.dp)) {
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
    }
}
