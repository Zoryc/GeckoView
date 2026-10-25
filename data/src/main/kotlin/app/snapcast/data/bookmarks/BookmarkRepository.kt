package app.snapcast.data.bookmarks

import kotlinx.coroutines.flow.Flow

data class Bookmark(
    val id: Long,
    val url: String,
    val title: String,
    val createdAt: Long,
    /** Ordering on the home screen / bookmarks list (ascending). */
    val position: Int,
    /** Shown as a speed-dial tile on the home page. */
    val onHome: Boolean,
)

interface BookmarkRepository {
    fun observeBookmarks(): Flow<List<Bookmark>>

    /** Bookmarks with [Bookmark.onHome], ordered by position. */
    fun observeSpeedDial(): Flow<List<Bookmark>>

    fun observeIsBookmarked(url: String): Flow<Boolean>

    suspend fun add(url: String, title: String, onHome: Boolean = false): Long

    suspend fun update(bookmark: Bookmark)

    suspend fun remove(id: Long)

    /** Adds or removes a bookmark for [url]; returns true when it is bookmarked afterwards. */
    suspend fun toggle(url: String, title: String): Boolean

    /** Inserts the default speed-dial video sites the first time the app runs. */
    suspend fun seedDefaultsIfNeeded()
}
