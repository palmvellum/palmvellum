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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import dev.tatliving.palmvellum.organizers.data.model.ExpenseFields
import dev.tatliving.palmvellum.organizers.data.model.expenseFieldsFrom
import dev.tatliving.palmvellum.organizers.data.model.toJson
import dev.tatliving.palmvellum.organizers.ui.MasterDetailScaffold
import dev.tatliving.palmvellum.organizers.ui.components.EditorScaffold
import dev.tatliving.palmvellum.organizers.ui.components.PalmCategoryStrip
import dev.tatliving.palmvellum.organizers.ui.components.PalmDivider
import dev.tatliving.palmvellum.organizers.ui.components.PalmEmptyState
import dev.tatliving.palmvellum.organizers.ui.components.PalmField
import dev.tatliving.palmvellum.organizers.ui.components.PalmListCard
import dev.tatliving.palmvellum.organizers.ui.components.PalmRow
import dev.tatliving.palmvellum.organizers.ui.components.TitleAction
import dev.tatliving.palmvellum.organizers.ui.components.TitleSearch
import dev.tatliving.palmvellum.organizers.ui.i18n.I18n
import dev.tatliving.palmvellum.organizers.ui.nav.Routes
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.util.DT
import dev.tatliving.palmvellum.organizers.util.pickDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// The classic Palm Expense pick-lists (mirrors the PWA's ExpenseLog).
private val EXPENSE_TYPES = listOf(
    "Airfare", "Breakfast", "Bus", "Business Meals", "Car Rental",
    "Dinner", "Entertainment", "Fax", "Gas", "Gifts",
    "Hotel", "Incidentals", "Laundry", "Limo", "Lodging",
    "Lunch", "Mileage", "Other", "Parking", "Postage",
    "Snack", "Subway", "Supplies", "Taxi", "Telephone",
    "Tips", "Tolls", "Train",
)
private val PAYMENT_TYPES = listOf(
    "American Express", "Cash", "Check", "Credit Card",
    "MasterCard", "Prepaid", "VISA", "Unfiled",
)

/** Stable key suffix for a stored English value, e.g. "Business Meals" -> "businessmeals". */
private fun typeSuffix(value: String): String =
    value.lowercase().replace(" ", "")

private fun expenseTypeLabel(value: String): String = I18n.t("expense.type." + typeSuffix(value))

private fun paymentTypeLabel(value: String): String = I18n.t("expense.pay." + typeSuffix(value))

class ExpenseViewModel : ViewModel() {
    private val repo = Graph.repo
    val expenses = repo.observeRecords("expense")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(r: RecordEntity) = viewModelScope.launch {
        repo.saveRecord(r)
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    }

    fun delete(id: String) = viewModelScope.launch { repo.deleteRecord(id) }

    fun refresh() = viewModelScope.launch { if (Graph.sync.isSignedIn) Graph.sync.syncNow() }
}

private fun expenseVendor(r: RecordEntity, f: ExpenseFields): String =
    f.palm_vendor?.ifBlank { null } ?: r.body?.ifBlank { null } ?: I18n.t("expense.noVendor")

/** "USD 12.50" — currency + amount, blank when no amount. */
private fun amountLabel(f: ExpenseFields): String? {
    val amt = f.palm_amount ?: return null
    val cur = f.palm_currency?.ifBlank { null } ?: "USD"
    val n = if (amt == amt.toLong().toDouble()) amt.toLong().toString() else "%.2f".format(amt)
    return "$cur $n"
}

@Composable
fun ExpenseScreen(navController: NavHostController) {
    val vm: ExpenseViewModel = viewModel()
    val expenses by vm.expenses.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<RecordEntity?>(null) }

    MasterDetailScaffold(
        title = I18n.t("expense.title"),
        navController = navController,
        currentRoute = Routes.EXPENSE,
        detail = editing,
        titleAction = { TitleAction(I18n.t("common.new")) { editing = newExpense() } },
        titleCenter = if (BuildConfig.COSMO) {
            { TitleSearch(query, { query = it }, placeholder = I18n.t("expense.searchHint")) }
        } else {
            null
        },
        placeholder = I18n.t("expense.pickPlaceholder"),
        master = {
            Column(Modifier.fillMaxSize()) {
                if (!BuildConfig.COSMO) {
                    PalmField(I18n.t("common.search"), query, { query = it })
                }
                // Memoised so selecting an expense doesn't re-map/filter every tap.
                val visible = remember(expenses, query) {
                    expenses
                        .map { it to expenseFieldsFrom(it.metadataJson) }
                        .filter { (rec, f) ->
                            query.isBlank() ||
                                listOfNotNull(
                                    expenseVendor(rec, f), f.palm_expense_type, f.palm_city, f.palm_payment,
                                ).any { it.contains(query, ignoreCase = true) }
                        }
                        .sortedByDescending { (rec, _) -> rec.createdAt }
                }
                if (visible.isEmpty()) {
                    PalmEmptyState(I18n.t("expense.empty"))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                        item {
                            PalmListCard {
                                visible.forEachIndexed { i, (rec, f) ->
                                    if (i > 0) PalmDivider()
                                    PalmRow(
                                        title = expenseVendor(rec, f),
                                        meta = amountLabel(f),
                                        body = listOfNotNull(
                                            f.palm_expense_type?.takeIf { it.isNotBlank() }?.let { expenseTypeLabel(it) },
                                            f.palm_payment?.takeIf { it.isNotBlank() }?.let { paymentTypeLabel(it) },
                                            f.palm_expense_date,
                                        ).filter { it.isNotBlank() }.joinToString("  ·  ").ifEmpty { null },
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
            key(target.id) {
                ExpenseEditor(
                    initial = target,
                    isNew = target.id.isEmpty(),
                    embedded = embedded,
                    onCancel = { editing = null },
                    onSave = { vm.save(it); editing = null },
                    onDelete = { vm.delete(target.id); editing = null },
                )
            }
        },
    )
}

private fun newExpense(): RecordEntity {
    val now = Clock.nowIso()
    return RecordEntity(id = "", type = "expense", body = "", createdAt = now, updatedAt = now)
}

@Composable
private fun ExpenseEditor(
    initial: RecordEntity,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (RecordEntity) -> Unit,
    onDelete: () -> Unit,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val f0 = expenseFieldsFrom(initial.metadataJson)
    var vendor by remember { mutableStateOf(f0.palm_vendor ?: initial.body ?: "") }
    var amount by remember { mutableStateOf(f0.palm_amount?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "") }
    var currency by remember { mutableStateOf(f0.palm_currency ?: "USD") }
    var type by remember { mutableStateOf(f0.palm_expense_type ?: "Other") }
    var payment by remember { mutableStateOf(f0.palm_payment ?: "Cash") }
    var date by remember { mutableStateOf(f0.palm_expense_date ?: DT.fmtDate(DT.nowDate())) }
    var city by remember { mutableStateOf(f0.palm_city ?: "") }
    var attendees by remember { mutableStateOf(f0.palm_attendees ?: "") }
    var notes by remember { mutableStateOf(f0.palm_notes ?: "") }

    val amountValue = amount.trim().toDoubleOrNull()
    val amountBad = amount.isNotBlank() && amountValue == null

    EditorScaffold(
        title = if (isNew) I18n.t("expense.newTitle") else I18n.t("expense.editTitle"),
        onCancel = onCancel,
        embedded = embedded,
        saveEnabled = (vendor.isNotBlank() || amount.isNotBlank()) && !amountBad,
        onSave = {
            val fields = ExpenseFields(
                palm_amount = amountValue,
                palm_currency = currency.trim().ifEmpty { "USD" },
                palm_vendor = vendor.trim().ifEmpty { null },
                palm_expense_type = type,
                palm_payment = payment,
                palm_expense_date = date.ifBlank { null },
                palm_city = city.trim().ifEmpty { null },
                palm_attendees = attendees.trim().ifEmpty { null },
                palm_notes = notes.trim().ifEmpty { null },
                palm_category_name = f0.palm_category_name ?: "Unfiled",
            )
            val body = vendor.trim().ifEmpty { "$type ${amountValue ?: ""}".trim() }
            onSave(
                initial.copy(
                    id = initial.id.ifEmpty { Ulid.new() },
                    body = body,
                    metadataJson = fields.toJson(),
                ),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PalmField(I18n.t("expense.vendor"), vendor, { vendor = it })
            DateTimeRow(
                label = I18n.t("expense.date"),
                value = date.ifBlank { I18n.t("expense.setDate") },
                onClick = {
                    val initialDate = runCatching { java.time.LocalDate.parse(date) }.getOrDefault(DT.nowDate())
                    pickDate(context, initialDate) { date = DT.fmtDate(it) }
                },
            )
            PalmField(
                I18n.t("expense.amount") + if (amountBad) "  " + I18n.t("expense.notANumber") else "",
                amount, { amount = it }, keyboardType = KeyboardType.Decimal,
            )
            PalmField(I18n.t("expense.currency"), currency, { currency = it })
            Text(I18n.t("expense.type"), color = PalmInkMute, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
            PalmCategoryStrip(EXPENSE_TYPES.map { it to expenseTypeLabel(it) }, type) { type = it }
            Text(I18n.t("expense.payment"), color = PalmInkMute, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
            PalmCategoryStrip(PAYMENT_TYPES.map { it to paymentTypeLabel(it) }, payment) { payment = it }
            PalmField(I18n.t("expense.city"), city, { city = it })
            PalmField(I18n.t("expense.attendees"), attendees, { attendees = it })
            PalmField(I18n.t("expense.notes"), notes, { notes = it }, singleLine = false, minLines = 2)
            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                DeleteButton(onDelete)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
