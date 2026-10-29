package app.snapcast.core.media

/**
 * HLS playlists as text, checked before a strict parser reads them (the phone player's Media3) or the proxy
 * rewrites them line by line. Browsers (hls.js) and the Chromecast tolerate byte order marks, blank lines before
 * `#EXTM3U` and old Mac line breaks; Media3 and a line-based rewriter do not, so they get a cleaned copy.
 */
object HlsPlaylistText {
    private const val HEADER = "#EXTM3U"
    private const val BOM = '﻿'

    /** What a response that should have been a playlist was instead. */
    enum class Instead(val description: String) {
        NOTHING("an empty answer"),
        WEB_PAGE("a web page"),
        JSON("a JSON message"),
        OTHER("something else"),
    }

    /**
     * [text] as a playlist Media3 and the proxy read like a browser does: byte order marks removed (at the start
     * and at the start of any line), blank lines before `#EXTM3U` dropped and lone CR line breaks made LF (CRLF
     * is kept). Null when it is not a playlist at all (see [whatInstead]).
     */
    fun clean(text: String): String? {
        var start = 0
        while (start < text.length && (text[start] == BOM || text[start].isWhitespace())) start++
        if (!text.startsWith(HEADER, start)) return null
        if (start == 0 && text.indexOf(BOM) < 0 && !hasLoneCr(text)) return text
        val out = StringBuilder(text.length - start)
        var lineStart = true
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                c == BOM && lineStart -> Unit
                c == '\r' && (i + 1 >= text.length || text[i + 1] != '\n') -> {
                    out.append('\n')
                    lineStart = true
                }
                else -> {
                    out.append(c)
                    lineStart = c == '\n'
                }
            }
            i++
        }
        return out.toString()
    }

    /**
     * Where `#EXTM3U` starts in the first [count] UTF-8 [bytes] of an answer when only byte order marks and blank
     * space come before it (0 for a clean playlist), or -1 when these bytes do not start a playlist.
     */
    fun headerOffset(bytes: ByteArray, count: Int = bytes.size): Int {
        var i = 0
        while (i < count) {
            val b = bytes[i].toInt() and 0xFF
            i += when {
                b == 0xEF && i + 2 < count && (bytes[i + 1].toInt() and 0xFF) == 0xBB && (bytes[i + 2].toInt() and 0xFF) == 0xBF -> 3
                b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D || b == 0x0C -> 1
                else -> break
            }
        }
        if (count - i < HEADER.length) return -1
        for (k in HEADER.indices) if (bytes[i + k].toInt() != HEADER[k].code) return -1
        return i
    }

    /** What [text] (the start of an answer that is not a playlist) looks like, to tell people what the site sent. */
    fun whatInstead(text: String): Instead {
        val head = text.trimStart { it == BOM || it.isWhitespace() }.take(512).lowercase()
        return when {
            head.isEmpty() -> Instead.NOTHING
            // Binary data (a key, a segment) decodes to control and replacement characters.
            head.any { it == '\uFFFD' || (it.isISOControl() && !it.isWhitespace()) } -> Instead.OTHER
            head.startsWith("<!doctype html") || head.startsWith("<html") || "<head" in head || "<body" in head -> Instead.WEB_PAGE
            head.startsWith("{") || head.startsWith("[") -> Instead.JSON
            else -> Instead.OTHER
        }
    }

    private fun hasLoneCr(text: String): Boolean {
        var i = text.indexOf('\r')
        while (i >= 0) {
            if (i + 1 >= text.length || text[i + 1] != '\n') return true
            i = text.indexOf('\r', i + 1)
        }
        return false
    }
}
