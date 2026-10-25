package app.snapcast.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Bookmark queries. Lists are ordered by `position` ascending (ties by id). */
@Dao
abstract class BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY position ASC, id ASC")
    abstract fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE onHome = 1 ORDER BY position ASC, id ASC")
    abstract fun observeOnHome(): Flow<List<BookmarkEntity>>

    /** Emits the ids bookmarked for [url] (empty or a single id, since urls are unique). */
    @Query("SELECT id FROM bookmarks WHERE url = :url")
    abstract fun observeIdsForUrl(url: String): Flow<List<Long>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    abstract suspend fun findByUrl(url: String): BookmarkEntity?

    /** Position right after the current last bookmark (0 when there are none). */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM bookmarks")
    abstract suspend fun nextPosition(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(bookmark: BookmarkEntity): Long

    /** Editing a bookmark to the URL of another bookmark replaces (merges into) that other bookmark. */
    @Update(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun update(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    abstract suspend fun deleteByUrl(url: String): Int

    /**
     * Appends a bookmark for [url], or — when [url] is already bookmarked — retitles it and puts it on the home
     * screen if [onHome] is set (it is never taken off). Returns the bookmark's id.
     */
    @Transaction
    open suspend fun addOrUpdate(url: String, title: String, onHome: Boolean, createdAt: Long): Long {
        val existing = findByUrl(url)
        if (existing != null) {
            val merged = existing.copy(title = title, onHome = existing.onHome || onHome)
            if (merged != existing) update(merged)
            return existing.id
        }
        return insert(
            BookmarkEntity(url = url, title = title, createdAt = createdAt, position = nextPosition(), onHome = onHome),
        )
    }

    /** Removes the bookmark for [url] if there is one, otherwise appends it. Returns true when bookmarked afterwards. */
    @Transaction
    open suspend fun toggle(url: String, title: String, createdAt: Long): Boolean {
        if (deleteByUrl(url) > 0) return false
        insert(BookmarkEntity(url = url, title = title, createdAt = createdAt, position = nextPosition(), onHome = false))
        return true
    }

    /**
     * Appends each of [entries] whose URL is not bookmarked yet, in order, after the current last bookmark
     * (their ids and positions are ignored). Returns how many were added.
     */
    @Transaction
    open suspend fun appendMissing(entries: List<BookmarkEntity>): Int {
        var position = nextPosition()
        var added = 0
        for (entry in entries) {
            if (findByUrl(entry.url) != null) continue
            insert(entry.copy(id = 0, position = position))
            position++
            added++
        }
        return added
    }
}
