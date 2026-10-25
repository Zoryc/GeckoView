package app.snapcast.data.bookmarks

/** A speed-dial site inserted on first run. */
internal data class DefaultBookmark(val title: String, val url: String)

/** Video sites shown on the home screen of a fresh install, in display order. */
internal object DefaultBookmarks {
    val ALL: List<DefaultBookmark> = listOf(
        DefaultBookmark("Internet Archive Movies", "https://archive.org/details/movies"),
        DefaultBookmark("Vimeo", "https://vimeo.com/"),
        DefaultBookmark("Dailymotion", "https://www.dailymotion.com/"),
        DefaultBookmark("NASA+", "https://plus.nasa.gov/"),
        DefaultBookmark("Wikimedia Commons videos", "https://commons.wikimedia.org/wiki/Category:Videos"),
        DefaultBookmark("Apple HLS examples", "https://developer.apple.com/streaming/examples/"),
    )

    /** Title stored for a bookmark: the trimmed [title], or the URL when the title is blank. */
    fun displayTitle(url: String, title: String): String = title.trim().ifEmpty { url }
}
