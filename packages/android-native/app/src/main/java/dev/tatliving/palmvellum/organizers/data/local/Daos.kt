package dev.tatliving.palmvellum.organizers.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE deletedAt IS NULL ORDER BY startAt ASC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: String): EventEntity?

    @Upsert
    suspend fun upsert(event: EventEntity)

    // ── sync ──
    @Query("SELECT * FROM events WHERE isDirty = 1")
    suspend fun dirty(): List<EventEntity>

    @Query("UPDATE events SET userId = :uid, isDirty = 1 WHERE userId IS NULL")
    suspend fun claim(uid: String)
}

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE type = :type AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeByType(type: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getById(id: String): RecordEntity?

    @Upsert
    suspend fun upsert(record: RecordEntity)

    // ── sync ──
    @Query("SELECT * FROM records WHERE isDirty = 1")
    suspend fun dirty(): List<RecordEntity>

    @Query("UPDATE records SET userId = :uid, isDirty = 1 WHERE userId IS NULL")
    suspend fun claim(uid: String)
}
