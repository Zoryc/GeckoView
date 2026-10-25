package app.snapcast.proxy.rewrite

import app.snapcast.core.util.UrlUtils

/**
 * Rewrites an HLS playlist (master or media) so every URI goes through the proxy. Line based: URI lines and
 * every `URI="..."` attribute of a tag (EXT-X-KEY, EXT-X-SESSION-KEY, EXT-X-MEDIA, EXT-X-MAP,
 * EXT-X-I-FRAME-STREAM-INF, EXT-X-PART, EXT-X-PRELOAD-HINT, EXT-X-RENDITION-REPORT, EXT-X-SESSION-DATA, ...)
 * are replaced; everything else, including line endings, is preserved byte for byte.
 *
 * Relative URIs are resolved against the playlist URL (the final URL after redirects). Non-http(s) URIs
 * (`data:`, `skd://`, ...) are left untouched.
 */
internal object HlsRewriter {
    private const val BOM = '﻿'

    fun rewrite(playlist: String, playlistUrl: String, mapper: ProxyUrlMapper): String {
        val out = StringBuilder(playlist.length + playlist.length / 2 + 256)
        var pos = 0
        if (playlist.startsWith(BOM)) {
            out.append(BOM)
            pos = 1
        }
        while (pos < playlist.length) {
            val newline = playlist.indexOf('\n', pos)
            val lineEnd = if (newline < 0) playlist.length else newline
            val contentEnd = if (lineEnd > pos && playlist[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
            out.append(rewriteLine(playlist.substring(pos, contentEnd), playlistUrl, mapper))
            val next = if (newline < 0) playlist.length else newline + 1
            out.append(playlist, contentEnd, next)
            pos = next
        }
        return out.toString()
    }

    private fun rewriteLine(line: String, playlistUrl: String, mapper: ProxyUrlMapper): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return line
        if (trimmed.startsWith("#")) {
            if (!trimmed.startsWith("#EXT")) return line
            val colon = line.indexOf(':')
            if (colon < 0) return line
            val tag = line.substring(0, colon).trim()
            // EXTINF carries a free-text title after the duration, never an attribute list.
            if (tag.equals("#EXTINF", ignoreCase = true)) return line
            return rewriteUriAttributes(line, colon + 1, playlistUrl, mapper)
        }
        val start = line.indexOf(trimmed)
        return line.substring(0, start) + rewriteUri(trimmed, playlistUrl, mapper) + line.substring(start + trimmed.length)
    }

    /** Rewrites the values of attributes named URI in the attribute list starting at [from]. */
    private fun rewriteUriAttributes(line: String, from: Int, playlistUrl: String, mapper: ProxyUrlMapper): String {
        if (!line.contains("URI", ignoreCase = true)) return line
        val out = StringBuilder(line.length + 128)
        out.append(line, 0, from)
        var i = from
        val n = line.length
        while (i < n) {
            while (i < n && (line[i] == ',' || line[i] == ' ' || line[i] == '\t')) out.append(line[i++])
            if (i >= n) break
            val nameStart = i
            while (i < n && line[i] != '=' && line[i] != ',') i++
            val isUri = line.substring(nameStart, i).trim().equals("URI", ignoreCase = true)
            out.append(line, nameStart, i)
            if (i >= n || line[i] == ',') continue
            out.append('=')
            i++
            if (i < n && line[i] == '"') {
                val close = line.indexOf('"', i + 1)
                val valueEnd = if (close < 0) n else close
                val value = line.substring(i + 1, valueEnd)
                out.append('"').append(if (isUri) rewriteUri(value, playlistUrl, mapper) else value)
                if (close >= 0) out.append('"')
                i = if (close < 0) n else close + 1
            } else {
                val comma = line.indexOf(',', i).let { if (it < 0) n else it }
                val value = line.substring(i, comma)
                if (isUri && value.isNotBlank()) {
                    val start = value.indexOf(value.trim())
                    out.append(value, 0, start)
                        .append(rewriteUri(value.trim(), playlistUrl, mapper))
                        .append(value, start + value.trim().length, value.length)
                } else {
                    out.append(value)
                }
                i = comma
            }
        }
        return out.toString()
    }

    private fun rewriteUri(uri: String, playlistUrl: String, mapper: ProxyUrlMapper): String {
        if (uri.isBlank()) return uri
        val resolved = UrlUtils.resolve(playlistUrl, uri)
        return if (UrlUtils.isHttp(resolved)) mapper.exact(resolved) else uri
    }
}
