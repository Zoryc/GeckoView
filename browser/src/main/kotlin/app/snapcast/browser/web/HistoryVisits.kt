package app.snapcast.browser.web

import org.mozilla.geckoview.GeckoSession.HistoryDelegate

/** Which `HistoryDelegate.onVisited` calls become history entries. */
internal object HistoryVisits {
    private val REDIRECT_SOURCE_FLAGS =
        HistoryDelegate.VISIT_REDIRECT_SOURCE or HistoryDelegate.VISIT_REDIRECT_SOURCE_PERMANENT

    /**
     * Top-level http(s) pages of normal tabs only: no subframes, no pages that merely redirected
     * elsewhere, no failed loads and nothing from private tabs.
     */
    fun shouldRecord(url: String, flags: Int, isPrivate: Boolean): Boolean {
        if (isPrivate) return false
        if (flags and HistoryDelegate.VISIT_TOP_LEVEL == 0) return false
        if (flags and REDIRECT_SOURCE_FLAGS != 0) return false
        if (flags and HistoryDelegate.VISIT_UNRECOVERABLE_ERROR != 0) return false
        val scheme = UriParts.schemeOf(url)
        return scheme == "http" || scheme == "https"
    }
}
