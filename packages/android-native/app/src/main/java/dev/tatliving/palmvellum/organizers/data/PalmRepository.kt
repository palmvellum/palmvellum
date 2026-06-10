package dev.tatliving.palmvellum.organizers.data

import dev.tatliving.palmvellum.organizers.data.local.DraftDao
import dev.tatliving.palmvellum.organizers.data.local.EventDao
import dev.tatliving.palmvellum.organizers.data.local.EventDraftEntity
import dev.tatliving.palmvellum.organizers.data.local.EventEntity
import dev.tatliving.palmvellum.organizers.data.local.ParsedEvent
import dev.tatliving.palmvellum.organizers.data.local.RecordDao
import dev.tatliving.palmvellum.organizers.data.local.RecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * Local-first repository: Room is the source of truth. Every write stamps
 * updatedAt + marks the row dirty so the sync engine can push it. Deletes
 * are soft (deletedAt) to survive sync.
 */
class PalmRepository(
    private val eventDao: EventDao,
    private val recordDao: RecordDao,
    private val draftDao: DraftDao,
) {
    // ── Date Book ───────────────────────────────────────────────
    fun observeEvents(): Flow<List<EventEntity>> = eventDao.observeAll()

    suspend fun getEvent(id: String): EventEntity? = eventDao.getById(id)

    suspend fun saveEvent(event: EventEntity) {
        eventDao.upsert(event.copy(updatedAt = Clock.nowIso(), isDirty = true, syncState = "local"))
    }

    suspend fun deleteEvent(id: String) {
        eventDao.getById(id)?.let {
            eventDao.upsert(it.copy(deletedAt = Clock.nowIso(), updatedAt = Clock.nowIso(), isDirty = true))
        }
    }

    // ── To Do / Address / Memo (records) ────────────────────────
    fun observeRecords(type: String): Flow<List<RecordEntity>> = recordDao.observeByType(type)

    suspend fun getRecord(id: String): RecordEntity? = recordDao.getById(id)

    suspend fun saveRecord(record: RecordEntity) {
        recordDao.upsert(record.copy(updatedAt = Clock.nowIso(), isDirty = true, syncState = "local"))
    }

    suspend fun deleteRecord(id: String) {
        recordDao.getById(id)?.let {
            recordDao.upsert(it.copy(deletedAt = Clock.nowIso(), updatedAt = Clock.nowIso(), isDirty = true))
        }
    }

    // ── Date Book AI drafts ─────────────────────────────────────
    fun observeDrafts(): Flow<List<EventDraftEntity>> = draftDao.observeActive()

    /** Create a "plan with AI" draft (the server parses it into events). */
    suspend fun createDraft(rawInput: String, userTz: String) {
        val now = Clock.nowIso()
        draftDao.upsert(
            EventDraftEntity(
                id = Ulid.new(),
                rawInput = rawInput.trim(),
                userTz = userTz,
                status = "pending",
                createdAt = now,
                isDirty = true,
            ),
        )
    }

    /** Accept the AI proposals: insert each parsed event, mark draft confirmed. */
    suspend fun acceptDraft(draft: EventDraftEntity, parsed: List<ParsedEvent>) {
        val now = Clock.nowIso()
        parsed.forEach { p ->
            eventDao.upsert(
                EventEntity(
                    id = Ulid.new(),
                    userId = draft.userId,
                    title = p.title.ifBlank { "(untitled)" },
                    startAt = p.start_at ?: now,
                    endAt = p.end_at,
                    allDay = p.all_day,
                    location = p.location,
                    notes = p.notes,
                    alarmMinutes = p.alarm_minutes,
                    createdAt = now,
                    updatedAt = now,
                    isDirty = true,
                ),
            )
        }
        draftDao.upsert(draft.copy(status = "confirmed", confirmedAt = now, isDirty = true))
    }

    suspend fun rejectDraft(draft: EventDraftEntity) {
        draftDao.upsert(draft.copy(status = "rejected", isDirty = true))
    }
}
