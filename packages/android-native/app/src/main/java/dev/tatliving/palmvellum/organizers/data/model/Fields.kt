package dev.tatliving.palmvellum.organizers.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Shared JSON codec for record metadata + (later) sync payloads. */
val PalmJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * To Do metadata (records.type='todo'). Key names match the PWA's
 * `metadata.palm_*` so cloud sync stays compatible.
 */
@Serializable
data class TodoFields(
    val palm_due_date: String? = null,   // yyyy-MM-dd
    val palm_priority: Int? = null,        // 1..5
    val palm_completed: Boolean = false,
    val palm_notes: String? = null,
    val palm_category_name: String? = null,
)

/**
 * Address metadata (records.type='contact'). P1 keeps a single phone +
 * email for simplicity; the PWA uses a phones[] array — P2 sync mapping
 * will reconcile.
 */
@Serializable
data class ContactFields(
    val palm_first_name: String? = null,
    val palm_last_name: String? = null,
    val palm_company: String? = null,
    val palm_title: String? = null,
    val palm_phone: String? = null,
    val palm_email: String? = null,
    val palm_notes: String? = null,
)

/**
 * Expense metadata (records.type='expense'). Vendor doubles as records.body.
 * Key names match the PWA's `metadata.palm_*` so cloud sync stays compatible.
 */
@Serializable
data class ExpenseFields(
    val palm_amount: Double? = null,
    val palm_currency: String? = null,
    val palm_vendor: String? = null,
    val palm_expense_type: String? = null,
    val palm_payment: String? = null,
    val palm_expense_date: String? = null, // yyyy-MM-dd
    val palm_city: String? = null,
    val palm_attendees: String? = null,
    val palm_notes: String? = null,
    val palm_category_name: String? = null,
)

fun TodoFields.toJson(): String = PalmJson.encodeToString(this)
fun ContactFields.toJson(): String = PalmJson.encodeToString(this)
fun ExpenseFields.toJson(): String = PalmJson.encodeToString(this)

fun todoFieldsFrom(json: String): TodoFields =
    runCatching { PalmJson.decodeFromString<TodoFields>(json) }.getOrDefault(TodoFields())

fun contactFieldsFrom(json: String): ContactFields =
    runCatching { PalmJson.decodeFromString<ContactFields>(json) }.getOrDefault(ContactFields())

fun expenseFieldsFrom(json: String): ExpenseFields =
    runCatching { PalmJson.decodeFromString<ExpenseFields>(json) }.getOrDefault(ExpenseFields())
