package app.snapcast.core.detection

import app.snapcast.core.model.AppSettings
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import app.snapcast.core.util.UrlUtils

/** User-configurable filters applied when exposing detected videos (so setting changes apply immediately). */
object DetectionFilter {
    /** Single-file formats; adaptive manifests are tiny text files, so the size rule never applies to them. */
    private val PROGRESSIVE_TYPES = setOf(StreamType.MP4, StreamType.WEBM, StreamType.MKV, StreamType.TS, StreamType.OTHER)

    /**
     * False when the video's media host or page host is in [AppSettings.ignoredDomains] (subdomains included),
     * a progressive file is smaller than [AppSettings.minVideoSizeBytes] (only when the size is known), or a
     * known duration is shorter than [AppSettings.minVideoDurationSec] (live streams and adaptive manifests
     * with unknown duration always pass).
     */
    fun isVisible(video: DetectedVideo, settings: AppSettings): Boolean {
        val hosts = listOfNotNull(UrlUtils.hostOf(video.url), video.pageUrl?.let(UrlUtils::hostOf))
        if (settings.ignoredDomains.any { domain -> hosts.any { UrlUtils.hostMatches(it, domain) } }) return false

        val size = video.sizeBytes
        if (video.type in PROGRESSIVE_TYPES && size != null && size < settings.minVideoSizeBytes) return false

        val duration = video.durationSec
        val minDuration = settings.minVideoDurationSec
        if (minDuration > 0 && !video.isLive && duration != null && duration > 0 && duration < minDuration) return false
        return true
    }

    fun apply(videos: List<DetectedVideo>, settings: AppSettings): List<DetectedVideo> = videos.filter { isVisible(it, settings) }
}
