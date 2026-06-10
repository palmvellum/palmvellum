package dev.tatliving.palmvellum.organizers.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A detected sync conflict: the same row was edited both locally and on
 * the server since the last sync. Held until the user resolves it on the
 * dedicated Conflicts screen (P3).
 */
@Entity(tableName = "conflicts")
data class ConflictEntity(
    @PrimaryKey val id: String,
    val entityTable: String,     // "events" | "records"
    val entityId: String,
    val entityType: String?,     // "event" | record type, for display
    val titleHint: String,       // short label for the list
    val localJson: String,
    val remoteJson: String,
    val localUpdatedAt: String,
    val remoteUpdatedAt: String,
    val detectedAt: String,
)

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts ORDER BY detectedAt DESC")
    fun observeAll(): Flow<List<ConflictEntity>>

    @Query("SELECT COUNT(*) FROM conflicts")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM conflicts WHERE entityTable = :table AND entityId = :id LIMIT 1")
    suspend fun forEntity(table: String, id: String): ConflictEntity?

    @Upsert
    suspend fun upsert(conflict: ConflictEntity)

    @Query("DELETE FROM conflicts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conflicts WHERE entityTable = :table AND entityId = :entityId")
    suspend fun deleteForEntity(table: String, entityId: String)
}
