package app.snapcast.cast.media

import app.snapcast.cast.HlsSegmentFormat
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.util.UrlUtils

/** Pure decisions used when turning detected videos into receiver media. */
internal object CastMediaRules {
    /** Content type the Default Media Receiver expects for HLS (it does not accept the Apple variant). */
    const val HLS_CONTENT_TYPE = "application/x-mpegURL"
    const val DASH_CONTENT_TYPE = "application/dash+xml"
    const val VTT_CONTENT_TYPE = "text/vtt"

    /** Used for progressive streams of unknown type: receivers reject wildcards like "video/ *". */
    private const val FALLBACK_CONTENT_TYPE = "video/mp4"

    private val HLS_MIME_TYPES = setOf(
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )

    /**
     * Content type announced to the receiver. HLS and DASH always use the receiver's canonical types; other
     * streams use the server's MIME type when it names a concrete audio/video type, else the type default.
     * For [StreamType.OTHER] the MIME type may still reveal a manifest.
     */
    fun contentType(type: StreamType, mimeType: String?): String {
        val rawMime = mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
        val mime = rawMime?.lowercase()
        return when (type) {
            StreamType.HLS -> HLS_CONTENT_TYPE
            StreamType.DASH -> DASH_CONTENT_TYPE
            StreamType.OTHER -> when {
                mime in HLS_MIME_TYPES -> HLS_CONTENT_TYPE
                mime == DASH_CONTENT_TYPE -> DASH_CONTENT_TYPE
                mime != null && isConcreteMediaType(mime) -> rawMime
                else -> FALLBACK_CONTENT_TYPE
            }
            else -> if (mime != null && isConcreteMediaType(mime)) rawMime else type.defaultMimeType
        }
    }

    private fun isConcreteMediaType(mime: String): Boolean =
        (mime.startsWith("video/") || mime.startsWith("audio/")) && !mime.endsWith("/*")

    /** HLS segment hint: fragmented MP4 (EXT-X-MAP) or MPEG-TS; null when unknown or not HLS. */
    fun hlsSegmentFormat(type: StreamType, fragmentedMp4: Boolean?): HlsSegmentFormat? {
        if (type != StreamType.HLS) return null
        return when (fragmentedMp4) {
            true -> HlsSegmentFormat.FMP4
            false -> HlsSegmentFormat.TS
            null -> null
        }
    }

    /**
     * True when the receiver can fetch the stream itself: no cookies (for the media host or its CDNs) and no
     * Authorization header, which a Chromecast cannot send.
     */
    fun canCastDirectly(video: DetectedVideo): Boolean =
        video.headers.cookie.isNullOrBlank() &&
            video.cookiesByHost.isEmpty() &&
            video.headers.extra.keys.none { it.equals("Authorization", ignoreCase = true) }

    /** Subtitles a receiver can load without the proxy: plain http(s) WebVTT. */
    fun isDirectSubtitle(track: SubtitleTrack): Boolean =
        !track.isLocal && UrlUtils.isHttp(track.url) && subtitleFormat(track) == SubtitleFormat.VTT

    /** Remote subtitles the proxy can fetch and serve as WebVTT (TTML cannot be converted). */
    fun isProxyableSubtitle(track: SubtitleTrack): Boolean =
        !track.isLocal && UrlUtils.isHttp(track.url) && subtitleFormat(track) != SubtitleFormat.TTML

    /** Declared format, or the one implied by the URL extension when undeclared. */
    fun subtitleFormat(track: SubtitleTrack): SubtitleFormat =
        if (track.format != SubtitleFormat.UNKNOWN) track.format else subtitleFormatFromUrl(track.url)

    fun subtitleFormatFromUrl(url: String): SubtitleFormat {
        val path = url.substringBefore('#').substringBefore('?')
        return when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
            "vtt", "webvtt" -> SubtitleFormat.VTT
            "srt" -> SubtitleFormat.SRT
            "ttml", "dfxp", "xml" -> SubtitleFormat.TTML
            else -> SubtitleFormat.UNKNOWN
        }
    }

    /** Same subtitle when the ids or the URLs match. */
    fun isSameSubtitle(a: SubtitleTrack, b: SubtitleTrack): Boolean = a.id == b.id || a.url == b.url

    /** Name shown in the receiver's track picker. */
    fun trackName(track: SubtitleTrack, trackId: Long): String =
        track.label?.takeIf { it.isNotBlank() }
            ?: track.language?.takeIf { it.isNotBlank() }
            ?: "Subtitles $trackId"

    /** Next free text-track id after [existing] (ids start at 1). */
    fun nextTrackId(existing: Collection<Long>): Long = (existing.maxOrNull() ?: 0L) + 1
}
