package app.snapcast.data.history

import java.util.Locale

/** Pure helpers behind [RoomHistoryRepository] (kept free of Android/Room so they are unit-testable). */
internal object HistoryQueries {
    /** Escape character used by the LIKE queries in [app.snapcast.data.db.HistoryDao]. */
    const val LIKE_ESCAPE = '\\'

    /** Internal / non-navigable schemes that never go into history. */
    private val UNRECORDED_SCHEMES = setOf(
        "about",
        "blob",
        "chrome",
        "data",
        "javascript",
        "moz-extension",
        "resource",
        "snapcast",
        "view-source",
    )

    /** True for URLs worth keeping in history (anything with a scheme except internal pages and data/blob URLs). */
    fun isRecordable(url: String): Boolean {
        if (url.isBlank()) return false
        val scheme = url.substringBefore(':', missingDelimiterValue = "").trim().lowercase(Locale.ROOT)
        return scheme.isNotEmpty() && scheme !in UNRECORDED_SCHEMES
    }

    /** Trimmed title, or null when it carries no information. */
    fun cleanTitle(title: String?): String? = title?.trim()?.takeIf { it.isNotEmpty() }

    /** Escapes LIKE wildcards (`%`, `_`) and the escape character itself in [text]. */
    fun escapeLike(text: String): String = buildString(text.length + 8) {
        for (c in text) {
            if (c == LIKE_ESCAPE || c == '%' || c == '_') append(LIKE_ESCAPE)
            append(c)
        }
    }

    /** LIKE pattern matching any value that contains [text] literally. */
    fun containsPattern(text: String): String = "%" + escapeLike(text) + "%"
}
