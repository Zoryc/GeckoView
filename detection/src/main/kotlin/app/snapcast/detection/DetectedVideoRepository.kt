package app.snapcast.detection

import app.snapcast.core.model.DetectedVideo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Videos found by the bundled detector extension, per tab. Lists are filtered with the user's
 * detection settings ([app.snapcast.core.detection.DetectionFilter]) and cleared when a tab loads a new page.
 */
interface DetectedVideoRepository {
    /** Visible videos of a tab in detection order; empty for unknown tabs. */
    fun videosForTab(tabId: String): Flow<List<DetectedVideo>>

    /** Visible videos of every tab, keyed by tab id. */
    val videosByTab: StateFlow<Map<String, List<DetectedVideo>>>

    /** Emits each newly visible video once (used to pulse the video button). */
    val newVideos: SharedFlow<DetectedVideo>

    /** True once the detector extension is installed and wired to sessions. */
    val isDetectorReady: StateFlow<Boolean>

    fun findVideo(tabId: String, videoId: String): DetectedVideo?

    /** Asks the tab's content scripts to rescan the DOM (e.g. when the video list is opened). */
    fun requestRescan(tabId: String)

    /**
     * Starts the tab's list over from what its page has now (the list's Refresh button): videos the page no
     * longer shows or loads are gone; a stream it still loads comes back with its next request.
     */
    fun refresh(tabId: String)

    /**
     * Asks the tab's frames for one small frame capture of each loaded video ([DetectedVideo.frameThumbnail]),
     * used where there is no poster or it does not load. Call it only while the list is shown: capturing costs
     * a little work in the page, so it never happens on its own.
     */
    fun requestThumbnails(tabId: String)

    fun clearTab(tabId: String)
}
