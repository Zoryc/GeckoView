package app.snapcast.ui.browser

import java.util.Locale

/** One address-bar suggestion. */
data class AddressSuggestion(
    val url: String,
    val title: String,
    val isBookmark: Boolean,
)

/** Merges bookmark and history matches into the suggestion dropdown list. */
object SuggestionMerger {
    const val DEFAULT_LIMIT = 8

    /**
     * Bookmarks whose title or URL contains [query] come first (in their order), then [history] entries
     * (already filtered by the repository, most recent first). De-duplicated by URL ignoring a trailing
     * slash and scheme/host case; blank queries give no suggestions.
     */
    fun merge(
        query: String,
        bookmarks: List<Pair<String, String>>,
        history: List<Pair<String, String>>,
        limit: Int = DEFAULT_LIMIT,
    ): List<AddressSuggestion> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<AddressSuggestion>(limit)
        fun offer(url: String, title: String, isBookmark: Boolean) {
            if (result.size >= limit || url.isBlank()) return
            if (seen.add(dedupeKey(url))) result += AddressSuggestion(url, title.ifBlank { url }, isBookmark)
        }
        bookmarks
            .filter { (url, title) -> url.contains(needle, ignoreCase = true) || title.contains(needle, ignoreCase = true) }
            .forEach { (url, title) -> offer(url, title, isBookmark = true) }
        history.forEach { (url, title) -> offer(url, title, isBookmark = false) }
        return result
    }

    internal fun dedupeKey(url: String): String {
        val trimmed = url.trim().removeSuffix("/")
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed
        val hostEnd = trimmed.indexOf('/', schemeEnd + 3).let { if (it < 0) trimmed.length else it }
        return trimmed.substring(0, hostEnd).lowercase(Locale.ROOT) + trimmed.substring(hostEnd)
    }
}
