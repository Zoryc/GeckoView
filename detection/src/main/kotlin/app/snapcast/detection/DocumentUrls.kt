package app.snapcast.detection

import app.snapcast.core.detection.MediaReport
import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.media.MediaKind
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
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

    /**
     * True when the URL of the top-level document says it is a stream (a link straight to an .mp4 or
     * .m3u8). Only a candidate: pages such as https://commons.wikimedia.org/wiki/File:Movie.webm are
     * HTML, so the document is probed ([probeTarget], StreamInspector.confirmDirectMedia) before it is listed.
     */
    fun isDirectMedia(url: String): Boolean =
        UrlUtils.isHttp(url) && MediaClassifier.classify(url, resourceType = RESOURCE_MAIN_FRAME) is MediaKind.Stream

    /** True when [known] (the tab's list) already holds the document [url], so there is nothing to probe. */
    fun isListed(url: String, known: List<DetectedVideo>): Boolean {
        val id = UrlUtils.stableId(UrlUtils.withoutHlsDirectives(url.trim()))
        return known.any { it.id == id }
    }

    /**
     * What the probe of the document [url] is sent as: the tab's headers when its list knows any (the
     * User-Agent its videos were fetched with, the cookies captured per host, newest last), else none.
     */
    fun probeTarget(url: String, tabId: String, isPrivate: Boolean, known: List<DetectedVideo>): DetectedVideo {
        val cookies = LinkedHashMap<String, String>()
        for (video in known) {
            cookies.putAll(video.cookiesByHost)
            val host = UrlUtils.hostOf(video.url)
            val cookie = video.headers.cookie
            if (host != null && !cookie.isNullOrBlank()) cookies[host] = cookie
        }
        val userAgent = known.asReversed().firstNotNullOfOrNull { it.headers.userAgent?.takeIf(String::isNotBlank) }
        return DetectedVideo(
            id = UrlUtils.stableId(url),
            tabId = tabId,
            url = url,
            type = MediaClassifier.typeFromUrl(url) ?: StreamType.OTHER,
            headers = MediaHeaders(userAgent = userAgent),
            cookiesByHost = cookies,
            isPrivate = isPrivate,
        )
    }

    /**
     * Report for a document that is itself a stream, once a probe confirmed it ([mime]: the type it
     * named, when specific). The extension reports it too (with the request headers); this one covers
     * media documents that run no content script.
     */
    fun directMediaReport(url: String, timestamp: Long, mime: String? = null): MediaReport = MediaReport(
        url = url,
        source = SOURCE_NETWORK,
        mime = mime,
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
