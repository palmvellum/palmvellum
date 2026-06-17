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

/**
 * Mail record metadata (records.type='mail'). The digest text lives in
 * records.body; these mirror the PWA's `metadata.mail_*` keys.
 */
@Serializable
data class MailFields(
    val mail_subject: String? = null,
    val mail_from: String? = null,
    val mail_source_name: String? = null,
    val mail_source_url: String? = null,
    val mail_source_type: String? = null,
    val mail_topic: String? = null,
    val mail_references: List<String>? = null,
    val mail_date_local: String? = null,
    val mail_fetched_at: String? = null,
)

/**
 * A row of the `mail_sources` table (subscriptions). Not a record — synced
 * directly via PostgREST, not through the local Room mirror.
 */
@Serializable
data class MailSource(
    val id: String,
    val user_id: String? = null,
    val name: String,
    val source_type: String = "url",
    val url: String? = null,
    val topic: String? = null,
    val fetch_time: String = "07:00:00",
    val timezone: String = "UTC",
    val enabled: Boolean = true,
    val digest_hint: String? = null,
    val output_language: String? = null,
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

fun mailFieldsFrom(json: String): MailFields =
    runCatching { PalmJson.decodeFromString<MailFields>(json) }.getOrDefault(MailFields())

/** A To Do task the Memo AI suggested; the user approves it before it's created. */
@Serializable
data class ProposedTodo(
    val description: String = "",
    val due_date: String? = null,
    val priority: Int? = null,
    val notes: String? = null,
)

/** Memo AI bookkeeping kept in a thought record's metadata. */
@Serializable
data class MemoAiFields(
    val proposed_todos: List<ProposedTodo> = emptyList(),
    val added_todos: List<String> = emptyList(),
)

fun memoAiFrom(json: String): MemoAiFields =
    runCatching { PalmJson.decodeFromString<MemoAiFields>(json) }.getOrDefault(MemoAiFields())
