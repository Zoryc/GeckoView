package app.snapcast.detection.inspect

/**
 * Decides whether a top-level document whose URL looks like a stream (it ends in ".webm", ".m3u8", ...)
 * really is one, from the answer to a HEAD request or to a ranged GET. The URL alone is not enough:
 * https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_4K.webm is an HTML page.
 *
 * Media: a `video/` type, or an HLS / DASH type. A generic type (or none) needs the first bytes of the
 * body, which must be there and must not look like HTML. Any other type (`text/html`, images, JSON) is
 * not media.
 */
internal object DirectMediaProbe {
    sealed interface Verdict

    /** The document is media; [mimeType] is its specific type, null when the server sent a generic one. */
    data class Media(val mimeType: String?) : Verdict

    data object NotMedia : Verdict

    /** Only the body can tell (HEAD failed, or the type is generic or missing). */
    data object Undecided : Verdict

    /** Bytes asked for (`Range: bytes=0-511`) and sniffed when the type is generic. */
    const val SNIFF_BYTES = 512

    private val HLS_MIMES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        "application/mpegurl",
    )
    private const val DASH_MIME = "application/dash+xml"

    /** Types servers send for anything (the same list MediaClassifier falls back to the URL for). */
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

    /**
     * Elements an HTML document starts with (the HTML patterns of the WHATWG MIME Sniffing standard,
     * section 7.1, plus a few common ones), each followed by a tag-terminating character.
     */
    private val HTML_TAGS = listOf(
        "<html", "<head", "<body", "<script", "<iframe", "<h1", "<div", "<font", "<table", "<a", "<style",
        "<title", "<b", "<br", "<p", "<meta", "<link", "<noscript", "<form", "<center", "<frameset",
    )
    private const val TAG_TERMINATORS = " >/\t\n\r\u000c"
    private const val UTF8_BOM = "ï»¿"

    /** Verdict from a HEAD response. */
    fun fromHead(code: Int, contentType: String?): Verdict {
        if (code !in 200..299) return Undecided
        return byType(baseMime(contentType)) ?: Undecided
    }

    /** Verdict from a (ranged) GET response whose body starts with [head]; never [Undecided]. */
    fun fromGet(code: Int, contentType: String?, head: ByteArray): Verdict {
        if (code !in 200..299) return NotMedia
        byType(baseMime(contentType))?.let { return it }
        return if (head.isEmpty() || looksLikeHtml(head)) NotMedia else Media(mimeType = null)
    }

    /** Media or not media from the type alone; null when the type is generic or missing. */
    private fun byType(mime: String?): Verdict? = when {
        mime == null || mime in GENERIC_MIMES -> null
        mime.startsWith("video/") || mime in HLS_MIMES || mime == DASH_MIME -> Media(mime)
        else -> NotMedia
    }

    /**
     * True when the first bytes of a body look like an HTML document: an HTML doctype, or an HTML element
     * first (after a byte order mark, white space, XML declarations and comments). An HLS playlist starts
     * with "#EXTM3U", a DASH manifest with `<MPD`, media files with binary signatures.
     */
    fun looksLikeHtml(head: ByteArray): Boolean {
        // ISO-8859-1 maps every byte to one char, so binary data cannot break the decoding.
        var text = String(head, Charsets.ISO_8859_1).removePrefix(UTF8_BOM).trimStart().lowercase()
        while (true) {
            text = when {
                text.startsWith("<!doctype") -> return text.startsWith("<!doctype html")
                // A comment longer than the sniffed bytes: pages open with those, playlists and media do not.
                text.startsWith("<!--") -> text.substringAfter("-->", MISSING).takeIf { it != MISSING }?.trimStart() ?: return true
                text.startsWith("<?") -> text.substringAfter("?>", "").trimStart()
                else -> break
            }
        }
        return HTML_TAGS.any { tag ->
            text.startsWith(tag) && (text.length == tag.length || text[tag.length] in TAG_TERMINATORS)
        }
    }

    private const val MISSING = "\u0000missing"

    private fun baseMime(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
