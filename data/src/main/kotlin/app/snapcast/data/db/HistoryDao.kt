package app.snapcast.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * History queries. Text filters take a LIKE pattern built with
 * [app.snapcast.data.history.HistoryQueries.containsPattern] (escape character `\`); SQLite's LIKE is
 * case-insensitive for ASCII.
 */
@Dao
abstract class HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC, id DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query(
        "SELECT * FROM history WHERE url LIKE :pattern ESCAPE '\\' OR title LIKE :pattern ESCAPE '\\' " +
            "ORDER BY visitedAt DESC, id DESC LIMIT :limit",
    )
    abstract fun observeMatching(pattern: String, limit: Int): Flow<List<HistoryEntity>>

    /**
     * Matching entries, one per URL (its most recent visit), most recent first. Relies on SQLite's rule that
     * bare columns of a `MAX()` aggregate query come from the row holding the maximum.
     */
    @Query(
        "SELECT id, url, title, MAX(visitedAt) AS visitedAt FROM history " +
            "WHERE url LIKE :pattern ESCAPE '\\' OR title LIKE :pattern ESCAPE '\\' " +
            "GROUP BY url ORDER BY visitedAt DESC LIMIT :limit",
    )
    abstract suspend fun suggestions(pattern: String, limit: Int): List<HistoryEntity>

    @Query("SELECT * FROM history ORDER BY visitedAt DESC, id DESC LIMIT 1")
    abstract suspend fun latest(): HistoryEntity?

    @Insert
    abstract suspend fun insert(entry: HistoryEntity): Long

    @Query("UPDATE history SET title = :title, visitedAt = :visitedAt WHERE id = :id")
    abstract suspend fun updateVisit(id: Long, title: String, visitedAt: Long)

    /** Retitles the most recent visit of [url] only, so older visits keep the title they had then. */
    @Query(
        "UPDATE history SET title = :title WHERE id = " +
            "(SELECT id FROM history WHERE url = :url ORDER BY visitedAt DESC, id DESC LIMIT 1)",
    )
    abstract suspend fun updateLatestTitle(url: String, title: String)

    @Query("DELETE FROM history WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    abstract suspend fun clear()

    /**
     * Records a visit: when the most recent row is for the same [url] it is moved to [visitedAt] (and retitled
     * when [title] is given); otherwise a new row is inserted.
     */
    @Transaction
    open suspend fun recordVisit(url: String, title: String?, visitedAt: Long) {
        val last = latest()
        if (last != null && last.url == url) {
            updateVisit(id = last.id, title = title ?: last.title, visitedAt = visitedAt)
        } else {
            insert(HistoryEntity(url = url, title = title.orEmpty(), visitedAt = visitedAt))
        }
    }
}
