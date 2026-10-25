package app.snapcast.detection

import app.snapcast.core.detection.VideoCollector
import org.mozilla.geckoview.WebExtension

/** Detection state of one tab. Main thread only. */
internal class TabDetection(val tabId: String, val isPrivate: Boolean, clock: () -> Long) {
    val collector = VideoCollector(tabId, isPrivate, clock)
    val inspections = InspectionTracker()

    /** Incremented whenever the list is reset, so late inspection results of an old document are dropped. */
    var generation = 0
        private set

    /** The top-frame content script's port, used to post commands. */
    var port: WebExtension.Port? = null

    /** Last URL the session reported for this tab (redirect targets and same-document navigations included). */
    var lastLocation: String? = null
        private set

    /** Title from the page's media session, used for videos that have no title of their own. */
    var mediaTitle: String? = null

    private var awaitingFirstLocation = false

    /** A new top-level document started loading at [url]. */
    fun startDocument(url: String) {
        resetDocument(url)
        lastLocation = url
        awaitingFirstLocation = true
    }

    /** Forgets every video (new document, or the user cleared the list). */
    fun resetDocument(pageUrl: String?) {
        collector.reset(pageUrl)
        inspections.clear()
        mediaTitle = null
        generation += 1
    }

    /** Records a location change; true for the first one after [startDocument] (the document's own URL). */
    fun onLocationChange(url: String): Boolean {
        lastLocation = url
        val first = awaitingFirstLocation
        awaitingFirstLocation = false
        return first
    }
}
