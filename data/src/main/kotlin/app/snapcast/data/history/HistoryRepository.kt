package app.snapcast.data.history

import kotlinx.coroutines.flow.Flow

data class HistoryEntry(
    val id: Long,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

interface HistoryRepository {
    /** Most recent first; [query] filters by url/title (case-insensitive substring) when not blank. */
    fun observeHistory(query: String = "", limit: Int = 500): Flow<List<HistoryEntry>>

    /** Records a top-level visit. Consecutive visits to the same URL update the existing row's time. */
    suspend fun recordVisit(url: String, title: String?)

    suspend fun updateTitle(url: String, title: String)

    /** Address-bar suggestions: entries whose url or title contains [text], most recent first, distinct by url. */
    suspend fun suggestions(text: String, limit: Int = 8): List<HistoryEntry>

    suspend fun delete(id: Long)

    suspend fun clear()
}
