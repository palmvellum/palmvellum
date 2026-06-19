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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import dev.tatliving.palmvellum.organizers.ui.i18n.I18n
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
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
    return name.ifBlank { f.palm_company ?: I18n.t("address.noName") }
}

@Composable
fun AddressScreen(navController: NavHostController) {
    val vm: AddressViewModel = viewModel()
    val contacts by vm.contacts.collectAsState()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<RecordEntity?>(null) }
    // Opening an existing contact shows a read-only card first; "edit" flips this
    // to the editor. A new contact (+ new) goes straight into editing.
    var editMode by remember { mutableStateOf(false) }

    MasterDetailScaffold(
        title = I18n.t("address.title"),
        navController = navController,
        currentRoute = Routes.ADDRESS,
        detail = editing,
        titleAction = { TitleAction(I18n.t("common.new")) { editing = newContact(); editMode = true } },
        // Cosmo: the search box rides in the title bar so the list keeps its height.
        titleCenter = if (BuildConfig.COSMO) {
            { TitleSearch(query, { query = it }, placeholder = I18n.t("address.searchContacts")) }
        } else {
            null
        },
        placeholder = I18n.t("address.placeholder"),
        master = {
            Column(Modifier.fillMaxSize()) {
                if (!BuildConfig.COSMO) {
                    PalmField(I18n.t("common.search"), query, { query = it })
                }
                // Memoised so selecting a contact (which recomposes the screen)
                // doesn't re-map/filter/sort the whole list every tap.
                val visible = remember(contacts, query) {
                    contacts
                        .map { it to contactFieldsFrom(it.metadataJson) }
                        .filter { (_, f) ->
                            query.isBlank() ||
                                listOfNotNull(
                                    f.palm_first_name, f.palm_last_name, f.palm_company, f.palm_phone, f.palm_email,
                                ).any { it.contains(query, ignoreCase = true) }
                        }
                        .sortedBy { (_, f) -> displayName(f).lowercase() }
                }
                if (visible.isEmpty()) {
                    PalmEmptyState(I18n.t("address.empty"))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                        item {
                            PalmListCard {
                                visible.forEachIndexed { i, (rec, f) ->
                                    if (i > 0) PalmDivider()
                                    PalmRow(
                                        title = displayName(f),
                                        meta = f.palm_phone,
                                        metaColor = PalmInk,
                                        metaBold = true,
                                        metaSize = 16.sp,
                                        body = listOfNotNull(f.palm_company, f.palm_email)
                                            .filter { it.isNotBlank() }.joinToString("  ").ifEmpty { null },
                                        onClick = { editing = rec; editMode = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        detailContent = { target, embedded ->
            // Key on the record id so tapping another contact while one is open
            // re-inits the fields instead of keeping the first one's state.
            key(target.id) {
                if (editMode || target.id.isEmpty()) {
                    ContactEditor(
                        initial = target,
                        isNew = target.id.isEmpty(),
                        embedded = embedded,
                        onCancel = { editing = null; editMode = false },
                        onSave = { vm.save(it); editing = null; editMode = false },
                        onDelete = { vm.delete(target.id); editing = null; editMode = false },
                    )
                } else {
                    ContactCard(
                        contact = target,
                        embedded = embedded,
                        onBack = { editing = null },
                        onEdit = { editMode = true },
                        onDelete = { vm.delete(target.id); editing = null },
                    )
                }
            }
        },
    )
}

/** Read-only view of a contact, shown when a contact is opened from the list.
 *  Reuses the editor frame but with "back" / "edit" header buttons. */
@Composable
private fun ContactCard(
    contact: RecordEntity,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    embedded: Boolean = false,
) {
    val f = contactFieldsFrom(contact.metadataJson)
    EditorScaffold(
        title = I18n.t("address.contact"),
        onCancel = onBack,
        onSave = onEdit,
        cancelLabel = I18n.t("common.back"),
        saveLabel = I18n.t("common.edit"),
        embedded = embedded,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(displayName(f), color = PalmInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val subtitle = listOfNotNull(f.palm_title, f.palm_company)
                .filter { it.isNotBlank() }.joinToString(", ")
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = PalmInkMute, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            val hasDetails = listOf(f.palm_phone, f.palm_email, f.palm_notes).any { !it.isNullOrBlank() }
            PalmListCard {
                Column(Modifier.padding(12.dp)) {
                    if (hasDetails) {
                        CardRow(I18n.t("address.phone"), f.palm_phone)
                        CardRow(I18n.t("address.email"), f.palm_email)
                        CardRow(I18n.t("address.notes"), f.palm_notes)
                    } else {
                        Text(I18n.t("address.noDetails"), color = PalmInkMute, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            DeleteButton(onDelete)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A labelled read-only row; renders nothing when the value is blank. */
@Composable
private fun CardRow(label: String, value: String?) {
    val v = value?.trim().orEmpty()
    if (v.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = PalmInkMute, fontSize = 12.sp)
        Text(v, color = PalmInk, fontSize = 16.sp)
    }
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
        title = if (isNew) I18n.t("address.newContact") else I18n.t("address.editContact"),
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
            PalmField(I18n.t("address.firstName"), first, { first = it })
            PalmField(I18n.t("address.lastName"), last, { last = it })
            PalmField(I18n.t("address.company"), company, { company = it })
            PalmField(I18n.t("address.titleField"), titleField, { titleField = it })
            PalmField(I18n.t("address.phone"), phone, { phone = it }, keyboardType = KeyboardType.Phone)
            PalmField(I18n.t("address.email"), email, { email = it }, keyboardType = KeyboardType.Email)
            PalmField(I18n.t("address.notes"), notes, { notes = it }, singleLine = false, minLines = 3)
            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                DeleteButton(onDelete)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
