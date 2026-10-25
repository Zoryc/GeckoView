package app.snapcast.core.media

import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat

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
 */
object MediaClassifier {
    /** Progressive files larger than this are listed even when they look like segments (e.g. a full .ts movie). */
    const val LARGE_FILE_BYTES: Long = 50L * 1024 * 1024

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
    ): MediaKind = TODO()

    /** Stream type implied by the URL path extension (or well-known query hints), or null. */
    fun typeFromUrl(url: String): StreamType? = TODO()

    /** Stream type implied by a Content-Type header value (parameters and case ignored), or null. */
    fun typeFromContentType(contentType: String?): StreamType? = TODO()

    fun subtitleFormatFromUrl(url: String): SubtitleFormat? = TODO()

    fun subtitleFormatFromContentType(contentType: String?): SubtitleFormat? = TODO()

    /** True when the URL looks like a chunk of an adaptive stream rather than a standalone file. */
    fun isSegmentUrl(url: String): Boolean = TODO()

    /** Sniffs the beginning of a body: "#EXTM3U" -> HLS, "<MPD" (after optional XML prolog/BOM) -> DASH. */
    fun sniffPlaylist(head: String): StreamType? = TODO()
}
