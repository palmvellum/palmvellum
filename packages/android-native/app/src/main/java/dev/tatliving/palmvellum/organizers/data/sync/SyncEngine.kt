package dev.tatliving.palmvellum.organizers.data.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tatliving.palmvellum.organizers.data.Clock
import dev.tatliving.palmvellum.organizers.data.Ulid
import dev.tatliving.palmvellum.organizers.data.local.ConflictDao
import dev.tatliving.palmvellum.organizers.data.local.ConflictEntity
import dev.tatliving.palmvellum.organizers.data.local.DraftDao
import dev.tatliving.palmvellum.organizers.data.local.EventDao
import dev.tatliving.palmvellum.organizers.data.local.EventDraftEntity
import dev.tatliving.palmvellum.organizers.data.local.EventEntity
import dev.tatliving.palmvellum.organizers.data.local.RecordDao
import dev.tatliving.palmvellum.organizers.data.local.RecordEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class SyncStatus { IDLE, SYNCING, SUCCESS, ERROR }

/**
 * Opt-in cloud sync against Supabase (PostgREST). Local Room is the source
 * of truth; this pushes dirty rows up and pulls remote changes down. When both
 * sides changed the same row since the last sync, we keep both versions (the
 * id adopts the cloud copy; the local copy is forked to a new row) and let the
 * user delete whichever they don't want — no manual conflict resolution.
 */
class SyncEngine(
    private val eventDao: EventDao,
    private val recordDao: RecordDao,
    private val conflictDao: ConflictDao,
    private val draftDao: DraftDao,
    private val session: SessionStore,
    private val rest: SupabaseRest,
) {
    var status by mutableStateOf(SyncStatus.IDLE)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var lastSyncedAt by mutableStateOf<String?>(null)
        private set

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val isSignedIn: Boolean get() = session.isSignedIn
    val email: String? get() = session.email

    // ── Auth (opt-in) ───────────────────────────────────────────
    suspend fun sendOtp(email: String): Result<Unit> =
        rest.sendOtp(email.trim().lowercase())

    suspend fun verifyOtp(email: String, token: String): Result<Unit> {
        val r = rest.verifyOtp(email.trim().lowercase(), token.trim())
        return if (r.isSuccess) {
            syncNow()
            Result.success(Unit)
        } else {
            Result.failure(r.exceptionOrNull() ?: Exception("verify failed"))
        }
    }

    /**
     * Upload a file to a Storage bucket (Memo Pad AI uploads). Requires an
     * active session; the server-side webhook then summarizes the file into
     * the matching records row.
     */
    suspend fun uploadObject(bucket: String, path: String, bytes: ByteArray, contentType: String): Result<Unit> =
        rest.uploadObject(bucket, path, bytes, contentType)

    /** Sign out: stop syncing. Local data is kept on the device. */
    fun signOut() {
        session.clear()
        status = SyncStatus.IDLE
        lastError = null
    }

    suspend fun syncNow(): Boolean {
        val uid = session.userId
        if (uid.isNullOrBlank() || !session.isSignedIn) return false
        status = SyncStatus.SYNCING
        lastError = null
        return try {
            // Adopt any local-only rows into this account so they get pushed.
            eventDao.claim(uid)
            recordDao.claim(uid)
            draftDao.claim(uid)
            // Pull first (detect conflicts, apply clean remote changes)...
            pull(uid)
            // ...then push the remaining dirty, non-conflicted rows.
            push(uid)
            lastSyncedAt = Clock.nowIso()
            status = SyncStatus.SUCCESS
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            status = SyncStatus.ERROR
            false
        }
    }

    // ── PULL ────────────────────────────────────────────────────
    private suspend fun pull(uid: String) {
        rest.select("events", "user_id=eq.$uid&select=*").getOrThrow().forEach { el ->
            mergeEvent(el.jsonObject)
        }
        rest.select("records", "user_id=eq.$uid&select=*").getOrThrow().forEach { el ->
            mergeRecord(el.jsonObject)
        }
        rest.select("event_drafts", "user_id=eq.$uid&select=*").getOrThrow().forEach { el ->
            mergeDraft(el.jsonObject)
        }
    }

    private suspend fun mergeDraft(remote: JsonObject) {
        val r = draftFromJson(remote)
        val local = draftDao.getById(r.id)
        // Drafts are server-authoritative (the AI fills parsed_events).
        // Only skip if we have an unpushed local change.
        if (local == null || !local.isDirty) draftDao.upsert(r)
    }

    private suspend fun mergeEvent(remote: JsonObject) {
        val remoteEntity = eventFromJson(remote)
        val local = eventDao.getById(remoteEntity.id)
        when {
            local == null -> eventDao.upsert(remoteEntity)
            !local.isDirty -> {
                if (local.remoteUpdatedAt != remoteEntity.updatedAt) eventDao.upsert(remoteEntity)
            }
            local.remoteUpdatedAt == remoteEntity.updatedAt -> Unit // only local changed -> push later
            else -> {
                // Both sides changed since the last sync. Rather than ask the
                // user to pick a winner, keep both: the existing id adopts the
                // remote (cloud) copy, and our local copy is re-inserted as a
                // brand-new event that pushes up as a separate row. The user
                // deletes whichever they don't want.
                eventDao.upsert(forkEvent(local))
                eventDao.upsert(remoteEntity)
            }
        }
    }

    private suspend fun mergeRecord(remote: JsonObject) {
        val remoteEntity = recordFromJson(remote)
        val local = recordDao.getById(remoteEntity.id)
        when {
            local == null -> recordDao.upsert(remoteEntity)
            !local.isDirty -> {
                if (local.remoteUpdatedAt != remoteEntity.updatedAt) recordDao.upsert(remoteEntity)
            }
            local.remoteUpdatedAt == remoteEntity.updatedAt -> Unit
            else -> {
                // Both sides changed: keep both versions (see mergeEvent).
                recordDao.upsert(forkRecord(local))
                recordDao.upsert(remoteEntity)
            }
        }
    }

    /** A fresh-id, dirty copy of a conflicting local row, pushed as a new row. */
    private fun forkEvent(local: EventEntity): EventEntity = local.copy(
        id = Ulid.new(),
        isDirty = true,
        syncState = "local",
        remoteUpdatedAt = null,
        updatedAt = Clock.nowIso(),
    )

    private fun forkRecord(local: RecordEntity): RecordEntity = local.copy(
        id = Ulid.new(),
        isDirty = true,
        syncState = "local",
        remoteUpdatedAt = null,
        updatedAt = Clock.nowIso(),
    )

    // ── PUSH ────────────────────────────────────────────────────
    private suspend fun push(uid: String) {
        eventDao.dirty().filter { it.syncState != "conflict" }.forEach { e ->
            val res = rest.upsert("events", listOf(eventToJson(e, uid)))
            res.getOrThrow()
            val serverUpdated = (res.getOrNull()?.firstOrNull() as? JsonObject)
                ?.get("updated_at")?.jsonPrimitive?.contentOrNull ?: e.updatedAt
            eventDao.upsert(e.copy(userId = uid, isDirty = false, syncState = "synced", remoteUpdatedAt = serverUpdated))
        }
        recordDao.dirty().filter { it.syncState != "conflict" }.forEach { r ->
            val res = rest.upsert("records", listOf(recordToJson(r, uid)))
            res.getOrThrow()
            val serverUpdated = (res.getOrNull()?.firstOrNull() as? JsonObject)
                ?.get("updated_at")?.jsonPrimitive?.contentOrNull ?: r.updatedAt
            recordDao.upsert(r.copy(userId = uid, isDirty = false, syncState = "synced", remoteUpdatedAt = serverUpdated))
        }
        draftDao.dirty().forEach { d ->
            rest.upsert("event_drafts", listOf(draftToJson(d, uid))).getOrThrow()
            draftDao.upsert(d.copy(userId = uid, isDirty = false, syncState = "synced"))
        }
    }

    // ── Conflict resolution (P3) ────────────────────────────────
    suspend fun resolveConflict(conflict: ConflictEntity, keepLocal: Boolean) {
        if (keepLocal) {
            // Re-mark local dirty so the next push overwrites remote.
            when (conflict.entityTable) {
                "events" -> eventDao.getById(conflict.entityId)?.let {
                    eventDao.upsert(it.copy(isDirty = true, syncState = "local", updatedAt = Clock.nowIso()))
                }
                else -> recordDao.getById(conflict.entityId)?.let {
                    recordDao.upsert(it.copy(isDirty = true, syncState = "local", updatedAt = Clock.nowIso()))
                }
            }
        } else {
            // Accept remote: overwrite local with the remote snapshot.
            when (conflict.entityTable) {
                "events" -> eventDao.upsert(eventFromJson(json.parseToJsonElement(conflict.remoteJson).jsonObject))
                else -> recordDao.upsert(recordFromJson(json.parseToJsonElement(conflict.remoteJson).jsonObject))
            }
        }
        conflictDao.deleteById(conflict.id)
        // Push immediately if we kept local.
        if (keepLocal) session.userId?.let { push(it) }
    }

    fun observeConflictCount() = conflictDao.observeCount()
    fun observeConflicts() = conflictDao.observeAll()

    // ── Mapping ─────────────────────────────────────────────────
    private fun eventToJson(e: EventEntity, uid: String): JsonObject = buildJsonObject {
        put("id", e.id)
        put("user_id", uid)
        put("title", e.title)
        put("start_at", e.startAt)
        put("end_at", e.endAt)
        put("all_day", e.allDay)
        put("location", e.location)
        put("notes", e.notes)
        put("alarm_minutes", e.alarmMinutes)
        put("repeat_rule", e.repeatRule)
        put("source", e.source)
        put("device_id", e.deviceId)
        put("created_at", e.createdAt)
        put("updated_at", e.updatedAt)
        put("deleted_at", e.deletedAt)
    }

    private fun recordToJson(r: RecordEntity, uid: String): JsonObject = buildJsonObject {
        put("id", r.id)
        put("user_id", uid)
        put("type", r.type)
        put("posture", r.posture)
        put("body", r.body)
        put("tags", runCatching { json.parseToJsonElement(r.tagsJson) }.getOrElse { JsonArray(emptyList()) })
        put("metadata", runCatching { json.parseToJsonElement(r.metadataJson) }.getOrElse { buildJsonObject {} })
        // Pending status drives the server AI webhooks (agent / upload summarizer).
        put("ai_status", r.aiStatus)
        put("source", r.source)
        put("device_id", r.deviceId)
        put("created_at", r.createdAt)
        put("updated_at", r.updatedAt)
        put("deleted_at", r.deletedAt)
    }

    private fun eventFromJson(o: JsonObject): EventEntity {
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        val updated = s("updated_at") ?: Clock.nowIso()
        return EventEntity(
            id = s("id") ?: Ulid.new(),
            userId = s("user_id"),
            title = s("title") ?: "",
            startAt = s("start_at") ?: Clock.nowIso(),
            endAt = s("end_at"),
            allDay = o["all_day"]?.jsonPrimitive?.booleanOrNull ?: false,
            location = s("location"),
            notes = s("notes"),
            alarmMinutes = o["alarm_minutes"]?.jsonPrimitive?.intOrNull,
            repeatRule = s("repeat_rule"),
            source = s("source") ?: "cloud",
            deviceId = s("device_id"),
            createdAt = s("created_at") ?: updated,
            updatedAt = updated,
            deletedAt = s("deleted_at"),
            isDirty = false,
            remoteUpdatedAt = updated,
            syncState = "synced",
        )
    }

    private fun recordFromJson(o: JsonObject): RecordEntity {
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        val updated = s("updated_at") ?: Clock.nowIso()
        val tags = (o["tags"] as? JsonArray)?.toString() ?: "[]"
        val metadata = (o["metadata"] as? JsonObject)?.toString() ?: "{}"
        return RecordEntity(
            id = s("id") ?: Ulid.new(),
            userId = s("user_id"),
            type = s("type") ?: "thought",
            posture = s("posture") ?: "open",
            body = s("body"),
            tagsJson = tags,
            metadataJson = metadata,
            source = s("source") ?: "cloud",
            deviceId = s("device_id"),
            createdAt = s("created_at") ?: updated,
            updatedAt = updated,
            deletedAt = s("deleted_at"),
            aiStatus = s("ai_status"),
            aiResponse = s("ai_response"),
            isDirty = false,
            remoteUpdatedAt = updated,
            syncState = "synced",
        )
    }

    private fun draftToJson(d: EventDraftEntity, uid: String): JsonObject = buildJsonObject {
        put("id", d.id)
        put("user_id", uid)
        put("raw_input", d.rawInput)
        put("user_tz", d.userTz)
        put("parsed_events", runCatching { json.parseToJsonElement(d.parsedEventsJson) }.getOrElse { JsonArray(emptyList()) })
        put("status", d.status)
        put("created_at", d.createdAt)
        put("confirmed_at", d.confirmedAt)
    }

    private fun draftFromJson(o: JsonObject): EventDraftEntity {
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        return EventDraftEntity(
            id = s("id") ?: Ulid.new(),
            userId = s("user_id"),
            rawInput = s("raw_input") ?: "",
            userTz = s("user_tz") ?: "UTC",
            parsedEventsJson = (o["parsed_events"] as? JsonArray)?.toString() ?: "[]",
            status = s("status") ?: "pending",
            aiError = s("ai_error"),
            createdAt = s("created_at") ?: Clock.nowIso(),
            processedAt = s("processed_at"),
            confirmedAt = s("confirmed_at"),
            isDirty = false,
            syncState = "synced",
        )
    }
}
