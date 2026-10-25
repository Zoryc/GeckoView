package app.snapcast.core.detection

import app.snapcast.core.model.AppSettings
import app.snapcast.core.model.DetectedVideo

/** User-configurable filters applied when exposing detected videos (so setting changes apply immediately). */
object DetectionFilter {
    /**
     * False when the video's media host or page host is in [AppSettings.ignoredDomains] (subdomains included),
     * a progressive file is smaller than [AppSettings.minVideoSizeBytes] (only when the size is known), or a
     * known duration is shorter than [AppSettings.minVideoDurationSec] (live streams and adaptive manifests
     * with unknown duration always pass).
     */
    fun isVisible(video: DetectedVideo, settings: AppSettings): Boolean = TODO()

    fun apply(videos: List<DetectedVideo>, settings: AppSettings): List<DetectedVideo> = videos.filter { isVisible(it, settings) }
}
