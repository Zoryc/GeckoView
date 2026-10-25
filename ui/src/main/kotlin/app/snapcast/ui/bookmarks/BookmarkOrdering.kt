package app.snapcast.ui.bookmarks

import app.snapcast.core.browser.UrlInputResolver
import app.snapcast.core.model.SearchEngine
import app.snapcast.data.bookmarks.Bookmark

/** Pure list arithmetic behind "Move up" / "Move down" of the bookmarks screen. */
internal object BookmarkOrdering {
    /**
     * The list after moving the bookmark [id] by [delta] places (clamped to the list bounds), or null when
     * [id] is unknown or the bookmark cannot move in that direction.
     */
    fun move(ordered: List<Bookmark>, id: Long, delta: Int): List<Bookmark>? {
        val from = ordered.indexOfFirst { it.id == id }
        if (from < 0 || delta == 0) return null
        val to = (from + delta).coerceIn(0, ordered.lastIndex)
        if (to == from) return null
        val result = ordered.toMutableList()
        result.add(to, result.removeAt(from))
        return result
    }

    /**
     * Copies of the bookmarks in [ordered] whose [Bookmark.position] differs from their index, with the index
     * as the new position. Saving them makes the stored order match [ordered] even when positions had gaps
     * or duplicates (e.g. the seeded speed dial).
     */
    fun renumber(ordered: List<Bookmark>): List<Bookmark> =
        ordered.mapIndexedNotNull { index, bookmark -> if (bookmark.position == index) null else bookmark.copy(position = index) }
}

/** Validation of what the user typed in the bookmark dialogs. */
internal object BookmarkInput {
    /**
     * Absolute URL for [text] ("example.com/video" becomes "https://example.com/video"), or null when it does
     * not look like an address. Search terms are never accepted.
     */
    fun normalizeUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !UrlInputResolver.looksLikeUrl(trimmed)) return null
        // Address-like input never becomes a search, so the engine passed here is never used.
        return UrlInputResolver.resolve(trimmed, SearchEngine.DUCKDUCKGO)
    }
}
