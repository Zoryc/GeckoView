package app.snapcast.detection

import app.snapcast.core.detection.PagePlaybackTracker
import app.snapcast.core.detection.VideoCollector
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.PagePlayback
import org.mozilla.geckoview.WebExtension

/** Detection state of one tab. Main thread only. */
internal class TabDetection(val tabId: String, val isPrivate: Boolean, private val clock: () -> Long) {
    val collector = VideoCollector(tabId, isPrivate, clock)
    val inspections = InspectionTracker()

    /** Which of the page's `<video>` elements its viewer watches. */
    val playback = PagePlaybackTracker()

    /** Incremented whenever the list is reset, so late inspection results of an old document are dropped. */
    var generation = 0
        private set

    /** The top-frame content script's port, used to post commands. */
    var port: WebExtension.Port? = null
        private set

    private var portConnectedAt = 0L
    private var documentStartedAt = 0L

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
        documentStartedAt = clock()
    }

    fun onPortConnected(newPort: WebExtension.Port) {
        port = newPort
        portConnectedAt = clock()
    }

    fun onPortDisconnected(oldPort: WebExtension.Port) {
        if (port === oldPort) port = null
    }

    /**
     * The port to post a rescan to, or null. A port opened before the current page started belongs
     * to the previous document; right after the page start that document is usually about to be
     * replaced, and its replay would bring its videos back, so it is only used once the navigation
     * evidently did not replace it (a download link, an aborted load).
     */
    fun rescanPort(): WebExtension.Port? {
        val current = port ?: return null
        val belongsToCurrentDocument = portConnectedAt >= documentStartedAt
        return current.takeIf { belongsToCurrentDocument || clock() - documentStartedAt > NAVIGATION_GRACE_MS }
    }

    /** Forgets every video (new document, or the user cleared the list). */
    fun resetDocument(pageUrl: String?) {
        collector.reset(pageUrl)
        inspections.clear()
        playback.clear()
        mediaTitle = null
        generation += 1
    }

    /** The tab's videos, the one the page's player plays (or paused last) first and marked. */
    fun videos(): List<DetectedVideo> {
        val videos = collector.videos
        val current = playback.current ?: return videos
        val id = collector.videoPlayedBy(current) ?: return videos
        return PagePlaybackTracker.mark(videos, id, if (current.paused) PagePlayback.PAUSED else PagePlayback.PLAYING)
    }

    /** Records a location change; true for the first one after [startDocument] (the document's own URL). */
    fun onLocationChange(url: String): Boolean {
        lastLocation = url
        val first = awaitingFirstLocation
        awaitingFirstLocation = false
        return first
    }

    private companion object {
        const val NAVIGATION_GRACE_MS = 10_000L
    }
}
