package dev.tatliving.palmvellum.organizers.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Date Book entry. Columns mirror the Supabase `events` table so the
 * P2 sync layer can map 1:1. Sync metadata (isDirty / remoteUpdatedAt /
 * syncState) is local-only and never pushed.
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val userId: String? = null,
    val title: String,
    val startAt: String,            // ISO-8601 instant (UTC)
    val endAt: String? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    val alarmMinutes: Int? = null,
    val repeatRule: String? = null,
    val source: String = "android-native",
    val deviceId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    // local-only sync metadata
    val isDirty: Boolean = true,
    val remoteUpdatedAt: String? = null,
    val syncState: String = "local",
)

/**
 * Catch-all record row (To Do / Address / Memo). `type` selects the app:
 * 'todo' | 'contact' | 'thought'. Structured fields live in `metadataJson`
 * (mirrors the Supabase `records.metadata` jsonb column).
 */
@Entity(tableName = "records")
data class RecordEntity(
    @PrimaryKey val id: String,
    val userId: String? = null,
    val type: String,
    val posture: String = "open",
    val body: String? = null,
    val tagsJson: String = "[]",
    val metadataJson: String = "{}",
    val source: String = "android-native",
    val deviceId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val aiStatus: String? = null,
    val aiResponse: String? = null,
    // local-only sync metadata
    val isDirty: Boolean = true,
    val remoteUpdatedAt: String? = null,
    val syncState: String = "local",
)
