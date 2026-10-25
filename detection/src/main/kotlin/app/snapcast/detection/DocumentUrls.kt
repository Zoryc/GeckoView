package app.snapcast.detection

import app.snapcast.core.detection.MediaReport
import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.media.MediaKind
import app.snapcast.core.util.UrlUtils

/** URL rules that decide when a tab's list belongs to a new document. */
internal object DocumentUrls {
    /**
     * True when a content script's hello comes from a document other than the one the tab's list
     * belongs to ([collectorPageUrl], set when the page started loading, or [lastLocation], the URL
     * the session reported since, e.g. after redirects or pushState): the page start was missed.
     * Fragments and trailing slashes are ignored.
     */
    fun isNewDocument(helloUrl: String, collectorPageUrl: String?, lastLocation: String?): Boolean {
        val hello = comparable(helloUrl) ?: return false
        return listOfNotNull(collectorPageUrl, lastLocation).none { comparable(it) == hello }
    }

    /** True when the top-level document itself is a stream (a link straight to an .mp4 or .m3u8). */
    fun isDirectMedia(url: String): Boolean =
        UrlUtils.isHttp(url) && MediaClassifier.classify(url, resourceType = RESOURCE_MAIN_FRAME) is MediaKind.Stream

    /**
     * Report for a document that is itself a stream. The extension reports it too (with the request
     * headers); this one covers media documents that run no content script.
     */
    fun directMediaReport(url: String, timestamp: Long): MediaReport = MediaReport(
        url = url,
        source = SOURCE_NETWORK,
        resourceType = RESOURCE_MAIN_FRAME,
        frameUrl = url,
        pageUrl = url,
        timestamp = timestamp,
    )

    private fun comparable(url: String): String? =
        url.trim().substringBefore('#').trimEnd('/').takeIf { it.isNotEmpty() }

    private const val RESOURCE_MAIN_FRAME = "main_frame"
    private const val SOURCE_NETWORK = "network"
}
