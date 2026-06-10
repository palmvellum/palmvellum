package dev.tatliving.palmvellum.organizers.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Free-form "plan with AI" request for Date Book. Mirrors the Supabase
 * `event_drafts` table. Created locally as status='pending'; the
 * process-event-draft Edge Function (server-side, user's BYOK key) parses
 * raw_input into `parsedEventsJson` and flips status to 'parsed'. The user
 * then reviews and accepts the parsed events.
 */
@Entity(tableName = "event_drafts")
data class EventDraftEntity(
    @PrimaryKey val id: String,
    val userId: String? = null,
    val rawInput: String,
    val userTz: String,
    val parsedEventsJson: String = "[]",
    val status: String = "pending", // pending|parsing|parsed|confirmed|rejected|error
    val aiError: String? = null,
    val createdAt: String,
    val processedAt: String? = null,
    val confirmedAt: String? = null,
    val isDirty: Boolean = true,
    val syncState: String = "local",
)

/** One AI-parsed event proposal (event_drafts.parsed_events element). */
@Serializable
data class ParsedEvent(
    val title: String = "",
    val start_at: String? = null,
    val end_at: String? = null,
    val all_day: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    val alarm_minutes: Int? = null,
)

@Dao
interface DraftDao {
    @Query("SELECT * FROM event_drafts WHERE status NOT IN ('confirmed','rejected') ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<EventDraftEntity>>

    @Query("SELECT * FROM event_drafts WHERE id = :id")
    suspend fun getById(id: String): EventDraftEntity?

    @Upsert
    suspend fun upsert(draft: EventDraftEntity)

    @Query("SELECT * FROM event_drafts WHERE isDirty = 1")
    suspend fun dirty(): List<EventDraftEntity>

    @Query("UPDATE event_drafts SET userId = :uid, isDirty = 1 WHERE userId IS NULL")
    suspend fun claim(uid: String)
}
