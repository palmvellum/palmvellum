package dev.tatliving.palmvellum.organizers.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import dev.tatliving.palmvellum.organizers.BuildConfig
import dev.tatliving.palmvellum.organizers.data.Clock
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.data.Ulid
import dev.tatliving.palmvellum.organizers.data.local.RecordEntity
import dev.tatliving.palmvellum.organizers.data.model.ContactFields
import dev.tatliving.palmvellum.organizers.data.model.contactFieldsFrom
import dev.tatliving.palmvellum.organizers.data.model.toJson
import dev.tatliving.palmvellum.organizers.ui.MasterDetailScaffold
import dev.tatliving.palmvellum.organizers.ui.components.EditorScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmDivider
import dev.tatliving.palmvellum.organizers.ui.components.PalmEmptyState
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.PalmListCard
import dev.tatliving.palmvellum.organizers.ui.components.PalmRow
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.components.TitleSearch
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AddressViewModel : ViewModel() {
    private val repo = Graph.repo
    val contacts = repo.observeRecords("contact")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(r: RecordEntity) = viewModelScope.launch { repo.saveRecord(r) }
    fun delete(id: String) = viewModelScope.launch { repo.deleteRecord(id) }
}

private fun displayName(f: ContactFields): String {
    val name = listOfNotNull(f.palm_first_name, f.palm_last_name)
        .filter { it.isNotBlank() }.joinToString(" ")
    return name.ifBlank { f.palm_company ?: "(no name)" }
}

@Composable
fun AddressScreen(navController: NavHostController) {
    val vm: AddressViewModel = viewModel()
    val contacts by vm.contacts.collectAsState()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<RecordEntity?>(null) }

    MasterDetailScaffold(
        title = "Address",
        navController = navController,
        currentRoute = Routes.ADDRESS,
        detail = editing,
        titleAction = { TitleAction("+ new") { editing = newContact() } },
        // Cosmo: the search box rides in the title bar so the list keeps its height.
        titleCenter = if (BuildConfig.COSMO) {
            { TitleSearch(query, { query = it }, placeholder = "search contacts") }
        } else {
            null
        },
        placeholder = "Pick a contact from the list, or tap + new.",
        master = {
            Column(Modifier.fillMaxSize()) {
                if (!BuildConfig.COSMO) {
                    PalmField("Search", query, { query = it })
                }
                val visible = contacts
                    .map { it to contactFieldsFrom(it.metadataJson) }
                    .filter { (_, f) ->
                        query.isBlank() ||
                            listOfNotNull(
                                f.palm_first_name, f.palm_last_name, f.palm_company, f.palm_phone, f.palm_email,
                            ).any { it.contains(query, ignoreCase = true) }
                    }
                    .sortedBy { (_, f) -> displayName(f).lowercase() }
                if (visible.isEmpty()) {
                    PalmEmptyState("No contacts.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                        item {
                            PalmListCard {
                                visible.forEachIndexed { i, (rec, f) ->
                                    if (i > 0) PalmDivider()
                                    PalmRow(
                                        title = displayName(f),
                                        meta = f.palm_phone,
                                        body = listOfNotNull(f.palm_company, f.palm_email)
                                            .filter { it.isNotBlank() }.joinToString("  ").ifEmpty { null },
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
            ContactEditor(
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

private fun newContact(): RecordEntity {
    val now = Clock.nowIso()
    return RecordEntity(id = "", type = "contact", body = "", createdAt = now, updatedAt = now)
}

@Composable
private fun ContactEditor(
    initial: RecordEntity,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (RecordEntity) -> Unit,
    onDelete: () -> Unit,
    embedded: Boolean = false,
) {
    val f0 = contactFieldsFrom(initial.metadataJson)
    var first by remember { mutableStateOf(f0.palm_first_name ?: "") }
    var last by remember { mutableStateOf(f0.palm_last_name ?: "") }
    var company by remember { mutableStateOf(f0.palm_company ?: "") }
    var titleField by remember { mutableStateOf(f0.palm_title ?: "") }
    var phone by remember { mutableStateOf(f0.palm_phone ?: "") }
    var email by remember { mutableStateOf(f0.palm_email ?: "") }
    var notes by remember { mutableStateOf(f0.palm_notes ?: "") }

    EditorScaffold(
        title = if (isNew) "New Contact" else "Edit Contact",
        onCancel = onCancel,
        embedded = embedded,
        saveEnabled = first.isNotBlank() || last.isNotBlank() || company.isNotBlank(),
        onSave = {
            val fields = ContactFields(
                palm_first_name = first.trim().ifEmpty { null },
                palm_last_name = last.trim().ifEmpty { null },
                palm_company = company.trim().ifEmpty { null },
                palm_title = titleField.trim().ifEmpty { null },
                palm_phone = phone.trim().ifEmpty { null },
                palm_email = email.trim().ifEmpty { null },
                palm_notes = notes.trim().ifEmpty { null },
            )
            onSave(
                initial.copy(
                    id = initial.id.ifEmpty { Ulid.new() },
                    body = displayName(fields),
                    metadataJson = fields.toJson(),
                ),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PalmField("First name", first, { first = it })
            PalmField("Last name", last, { last = it })
            PalmField("Company", company, { company = it })
            PalmField("Title", titleField, { titleField = it })
            PalmField("Phone", phone, { phone = it }, keyboardType = KeyboardType.Phone)
            PalmField("E-mail", email, { email = it }, keyboardType = KeyboardType.Email)
            PalmField("Notes", notes, { notes = it }, singleLine = false, minLines = 3)
            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                DeleteButton(onDelete)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
