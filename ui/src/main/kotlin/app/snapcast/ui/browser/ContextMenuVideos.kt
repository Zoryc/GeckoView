package app.snapcast.ui.browser

import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DetectionSource
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import app.snapcast.core.util.UrlUtils

/** Turns the `src` of a long-pressed `<video>` element into a castable [DetectedVideo]. */
object ContextMenuVideos {
    /** Page context of the long-press. */
    data class Page(
        val tabId: String,
        val url: String?,
        val title: String?,
        val isPrivate: Boolean,
    )

    /**
     * - A video already detected in the tab with the same (normalized) URL is reused, keeping its headers,
     *   variants and DRM flag.
     * - `blob:` / MediaSource sources cannot be fetched; the tab's most recent adaptive stream (or most
     *   recent video) stands in for them, since that is what the element is playing.
     * - Other http(s) sources become a new DOM video whose Referer is the page URL.
     * Returns null when nothing castable can be derived.
     */
    fun resolve(
        srcUrl: String?,
        elementTitle: String?,
        page: Page,
        detected: List<DetectedVideo>,
        nowMs: Long,
    ): DetectedVideo? {
        val src = srcUrl?.trim().orEmpty()
        if (src.isEmpty()) return null
        detected.firstOrNull { it.url == src }?.let { return it }
        if (src.startsWith("blob:", ignoreCase = true) || src.startsWith("mediasource:", ignoreCase = true)) {
            return detected.lastOrNull { it.type.isAdaptive } ?: detected.lastOrNull()
        }
        if (!UrlUtils.isHttp(src)) return null
        val normalized = UrlUtils.normalizeForDedupe(src)
        detected.firstOrNull { UrlUtils.normalizeForDedupe(it.url) == normalized }?.let { return it }
        val referer = page.url?.takeIf { UrlUtils.isHttp(it) }
        return DetectedVideo(
            id = UrlUtils.stableId(src),
            tabId = page.tabId,
            url = src,
            type = MediaClassifier.typeFromUrl(src) ?: StreamType.OTHER,
            pageUrl = page.url,
            pageTitle = page.title,
            title = elementTitle?.takeIf { it.isNotBlank() },
            headers = MediaHeaders(referer = referer),
            source = DetectionSource.DOM,
            detectedAt = nowMs,
            isPrivate = page.isPrivate,
        )
    }
}
