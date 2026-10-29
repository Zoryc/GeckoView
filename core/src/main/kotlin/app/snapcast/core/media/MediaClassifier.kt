package app.snapcast.core.media

import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.util.UriReference
import app.snapcast.core.util.UrlUtils

/** Result of classifying a network resource or a DOM media source. */
sealed interface MediaKind {
    /** A playable stream worth listing (progressive file or adaptive manifest). */
    data class Stream(val type: StreamType) : MediaKind

    /** A chunk of an adaptive stream (HLS .ts/.m4s/.aac, DASH segment, MSE byte range). Never listed. */
    data object Segment : MediaKind

    data class Subtitle(val format: SubtitleFormat) : MediaKind

    data object NotMedia : MediaKind
}

/**
 * Classifies URLs / MIME types as media. Used by the detector (Kotlin side is the source of truth;
 * the bundled extension only pre-filters) and by the proxy to decide when to rewrite playlists.
 *
 * A specific Content-Type wins over the URL; generic or commonly misconfigured types
 * (`application/octet-stream`, `text/plain`, `application/xml`, missing, ...) fall back to the URL.
 */
object MediaClassifier {
    /** Progressive files larger than this are listed even when they look like segments (e.g. a full .ts movie). */
    const val LARGE_FILE_BYTES: Long = 50L * 1024 * 1024

    /**
     * MP4/WebM fetched by XHR/fetch are Media Source Extensions chunks unless at least this big
     * (some players download a whole short file through XHR before handing it to MSE).
     */
    private const val MSE_FULL_FILE_BYTES: Long = 20L * 1024 * 1024

    private const val RESOURCE_XHR = "xmlhttprequest"

    private val HLS_MIMES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        "application/mpegurl",
    )
    private const val DASH_MIME = "application/dash+xml"

    /** Types servers use for anything; the URL decides. */
    private val GENERIC_MIMES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "text/plain",
        "application/xml",
        "text/xml",
        "application/binary",
        "application/unknown",
        "application/x-unknown",
        "application/download",
        "application/x-download",
        "application/force-download",
        "application/mp4",
        "content/unknown",
        "unknown/unknown",
        "*/*",
    )

    /** MIME types used exclusively for adaptive-stream chunks. */
    private val SEGMENT_MIMES = setOf("video/iso.segment", "audio/iso.segment")

    /** Extensions that are always chunks of an adaptive stream (".ts" is handled separately: it may be a full movie). */
    private val SEGMENT_EXTENSIONS = setOf("m4s", "m4f", "cmfv", "cmfa", "cmft", "aac", "fmp4")

    /** Extensions an init segment may carry ("init.mp4", "init-v1.m4s", "video_init.cmfv"). */
    private val INIT_SEGMENT_EXTENSIONS = setOf("mp4", "m4v", "m4a", "m4s", "m4f", "cmfv", "cmfa", "fmp4", "webm", "ts")

    private val SEGMENT_NAME = Regex("(?:^|[^a-z])(?:segment|seg|chunk|fragment|frag)[-_]?\\d+|filesequence\\d+")
    private val NUMERIC_SEGMENT_NAME = Regex("\\d+\\.(?:ts|m4s|m4f|cmfv|cmfa|aac|fmp4|m4a|vtt|webvtt)")
    private val INIT_SEGMENT_NAME = Regex("(?:^|.*[^a-z])init(?:[^a-z].*)?")
    private val BYTE_RANGE_PARAMS = setOf("range", "bytes", "bytestart", "byteend")
    private val HLS_QUERY_HINT = Regex("[?&;(,]format=m3u8")
    private val DASH_QUERY_HINT = Regex("[?&;(,]format=mpd")

    /**
     * @param url absolute URL of the resource.
     * @param contentType value of the Content-Type response header, may include parameters.
     * @param resourceType WebExtension webRequest resource type ("media", "xmlhttprequest", "main_frame", ...), or null.
     * @param contentLength total size in bytes if known.
     */
    fun classify(
        url: String,
        contentType: String? = null,
        resourceType: String? = null,
        contentLength: Long? = null,
    ): MediaKind {
        val mime = baseMime(contentType)
        if (mime == null || mime in GENERIC_MIMES) return classifyByUrl(url, resourceType, contentLength)

        typeFromMime(mime)?.let { return streamOrSegment(it, url, resourceType, contentLength) }
        subtitleFormatFromMime(mime)?.let { return MediaKind.Subtitle(it) }
        if (mime in SEGMENT_MIMES) return MediaKind.Segment
        if (mime.startsWith("video/")) {
            // An unusual video subtype: the URL may still tell us more, otherwise it is a generic video.
            return when (val byUrl = classifyByUrl(url, resourceType, contentLength)) {
                is MediaKind.Stream, MediaKind.Segment -> byUrl
                else -> streamOrSegment(StreamType.OTHER, url, resourceType, contentLength)
            }
        }
        return MediaKind.NotMedia
    }

    private fun classifyByUrl(url: String, resourceType: String?, contentLength: Long?): MediaKind {
        subtitleFormatFromUrl(url)?.let { return MediaKind.Subtitle(it) }
        val ext = UrlUtils.extensionOf(url)
        if (ext in SEGMENT_EXTENSIONS) return MediaKind.Segment
        val type = typeFromUrl(url)
        return when {
            type != null -> streamOrSegment(type, url, resourceType, contentLength)
            isSegmentUrl(url) -> MediaKind.Segment
            else -> MediaKind.NotMedia
        }
    }

    private fun streamOrSegment(type: StreamType, url: String, resourceType: String?, contentLength: Long?): MediaKind {
        val large = contentLength != null && contentLength >= LARGE_FILE_BYTES
        return when (type) {
            StreamType.HLS, StreamType.DASH -> MediaKind.Stream(type)
            StreamType.TS -> if (large) MediaKind.Stream(type) else MediaKind.Segment
            else -> {
                val mseChunk = (type == StreamType.MP4 || type == StreamType.WEBM) &&
                    resourceType.equals(RESOURCE_XHR, ignoreCase = true) &&
                    (contentLength == null || contentLength < MSE_FULL_FILE_BYTES)
                when {
                    !large && isSegmentUrl(url) -> MediaKind.Segment
                    mseChunk -> MediaKind.Segment
                    else -> MediaKind.Stream(type)
                }
            }
        }
    }

    /** Stream type implied by the URL path extension (or well-known query hints), or null. */
    fun typeFromUrl(url: String): StreamType? {
        when (UrlUtils.extensionOf(url)) {
            "m3u8" -> return StreamType.HLS
            "mpd" -> return StreamType.DASH
            "mp4", "m4v" -> return StreamType.MP4
            "webm" -> return StreamType.WEBM
            "mkv" -> return StreamType.MKV
            "mov" -> return StreamType.OTHER
            "ts" -> return StreamType.TS
        }
        val lower = url.substringBefore('#').lowercase()
        return when {
            HLS_QUERY_HINT.containsMatchIn(lower) -> StreamType.HLS
            DASH_QUERY_HINT.containsMatchIn(lower) -> StreamType.DASH
            else -> null
        }
    }

    /** Stream type implied by a Content-Type header value (parameters and case ignored), or null. */
    fun typeFromContentType(contentType: String?): StreamType? = baseMime(contentType)?.let(::typeFromMime)

    private fun typeFromMime(mime: String): StreamType? = when (mime) {
        in HLS_MIMES -> StreamType.HLS
        DASH_MIME -> StreamType.DASH
        "video/mp4", "video/x-m4v" -> StreamType.MP4
        "video/webm" -> StreamType.WEBM
        "video/x-matroska", "video/matroska" -> StreamType.MKV
        "video/quicktime" -> StreamType.OTHER
        "video/mp2t" -> StreamType.TS
        else -> null
    }

    fun subtitleFormatFromUrl(url: String): SubtitleFormat? = when (UrlUtils.extensionOf(url)) {
        "vtt", "webvtt" -> SubtitleFormat.VTT
        "srt" -> SubtitleFormat.SRT
        "ttml", "dfxp" -> SubtitleFormat.TTML
        else -> null
    }

    fun subtitleFormatFromContentType(contentType: String?): SubtitleFormat? = baseMime(contentType)?.let(::subtitleFormatFromMime)

    private fun subtitleFormatFromMime(mime: String): SubtitleFormat? = when (mime) {
        "text/vtt" -> SubtitleFormat.VTT
        "application/x-subrip", "text/srt", "application/srt", "text/x-srt" -> SubtitleFormat.SRT
        "application/ttml+xml" -> SubtitleFormat.TTML
        else -> null
    }

    /** True when the URL looks like a chunk of an adaptive stream rather than a standalone file. */
    fun isSegmentUrl(url: String): Boolean {
        val ref = UriReference.parse(url.trim())
        if (ref.isOpaque) return false
        val path = ref.path.lowercase()
        val name = ref.lastSegment.lowercase()
        val ext = UrlUtils.extensionOf(url)
        return ext == "ts" ||
            ext in SEGMENT_EXTENSIONS ||
            SEGMENT_NAME.containsMatchIn(path) ||
            NUMERIC_SEGMENT_NAME.matches(name) ||
            (INIT_SEGMENT_NAME.matches(name.substringBeforeLast('.')) && (ext == null || ext in INIT_SEGMENT_EXTENSIONS)) ||
            (path.endsWith("/videoplayback") && ref.query != null) ||
            hasByteRangeParam(ref.query)
    }

    private fun hasByteRangeParam(query: String?): Boolean {
        if (query.isNullOrEmpty()) return false
        return query.split('&').any { it.contains('=') && it.substringBefore('=').lowercase() in BYTE_RANGE_PARAMS }
    }

    /** Sniffs the beginning of a body: "#EXTM3U" -> HLS, "<MPD" (after optional XML prolog/BOM) -> DASH. */
    fun sniffPlaylist(head: String): StreamType? {
        // Any number of byte order marks and blank lines may come first (concatenated or re-encoded files).
        var text = head.trimStart { it == '\uFEFF' || it.isWhitespace() }
        if (text.startsWith("#EXTM3U")) return StreamType.HLS
        while (true) {
            text = when {
                text.startsWith("<?") -> text.substringAfter("?>", "").trimStart()
                text.startsWith("<!--") -> text.substringAfter("-->", "").trimStart()
                text.startsWith("<!") -> text.substringAfter('>', "").trimStart()
                else -> break
            }
        }
        return if (MPD_ROOT.containsMatchIn(text)) StreamType.DASH else null
    }

    /** Root element named MPD, with an optional namespace prefix; the head may end right after the name. */
    private val MPD_ROOT = Regex("^<(?:[A-Za-z_][\\w.\\-]*:)?MPD(?:[\\s>/]|$)")

    /** Lower-cased MIME type without parameters, or null when blank. */
    private fun baseMime(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
