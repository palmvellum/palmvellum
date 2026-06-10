package dev.tatliving.palmvellum.organizers.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventEntity::class,
        RecordEntity::class,
        ConflictEntity::class,
        EventDraftEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class PalmDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun recordDao(): RecordDao
    abstract fun conflictDao(): ConflictDao
    abstract fun draftDao(): DraftDao
}
