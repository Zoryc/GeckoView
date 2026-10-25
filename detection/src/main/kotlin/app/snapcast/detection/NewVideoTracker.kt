package app.snapcast.detection

import app.snapcast.core.model.DetectedVideo

/** Remembers which videos were announced per tab, so each visible video is announced once per document. */
internal class NewVideoTracker {
    private val announced = HashMap<String, MutableSet<String>>()

    /** Videos of [visibleByTab] that were not announced before, in tab and list order; marks them announced. */
    fun newlyVisible(visibleByTab: Map<String, List<DetectedVideo>>): List<DetectedVideo> {
        val result = ArrayList<DetectedVideo>()
        for ((tabId, videos) in visibleByTab) {
            if (videos.isEmpty()) continue
            val ids = announced.getOrPut(tabId) { HashSet() }
            for (video in videos) {
                if (ids.add(video.id)) result += video
            }
        }
        return result
    }

    /** The tab shows a new document (or was closed): its videos may be announced again. */
    fun resetTab(tabId: String) {
        announced.remove(tabId)
    }
}
