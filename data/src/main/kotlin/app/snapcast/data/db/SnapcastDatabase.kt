package app.snapcast.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** The app's Room database: browsing history, bookmarks and resume positions. */
@Database(
    entities = [HistoryEntity::class, BookmarkEntity::class, PlaybackPositionEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class SnapcastDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun playbackPositionDao(): PlaybackPositionDao

    companion object {
        const val FILE_NAME = "snapcast.db"
    }
}
