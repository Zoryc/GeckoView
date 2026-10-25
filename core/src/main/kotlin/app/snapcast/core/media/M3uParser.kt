package app.snapcast.core.media

import app.snapcast.core.util.UrlUtils

/** One entry of an IPTV / extended M3U playlist. */
data class M3uEntry(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val durationSec: Double? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/** Parses simple and extended (#EXTM3U / #EXTINF) M3U playlists used for IPTV lists. */
object M3uParser {
    private const val EXTINF = "#EXTINF:"
    private const val EXTGRP = "#EXTGRP:"
    private const val EXTVLCOPT = "#EXTVLCOPT:"

    /** Tags that only appear in HLS playlists; their presence means this is a stream, not a channel list. */
    private val HLS_TAGS = listOf(
        "#EXT-X-TARGETDURATION",
        "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-STREAM-INF",
        "#EXT-X-I-FRAME-STREAM-INF",
        "#EXT-X-ENDLIST",
        "#EXT-X-MAP",
        "#EXT-X-PLAYLIST-TYPE",
    )

    private val LINE_BREAK = Regex("\r\n|\r|\n")

    /** Relative entries are resolved against [baseUrl] when given. Returns empty list for HLS media playlists. */
    fun parse(text: String, baseUrl: String? = null): List<M3uEntry> {
        val lines = text.removePrefix("﻿").split(LINE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { line -> HLS_TAGS.any { line.startsWith(it, ignoreCase = true) } }) return emptyList()

        val entries = mutableListOf<M3uEntry>()
        var info: ExtInf? = null
        var group: String? = null
        val options = LinkedHashMap<String, String>()
        for (line in lines) {
            when {
                line.startsWith(EXTINF, ignoreCase = true) -> info = parseExtInf(line.substring(EXTINF.length))
                line.startsWith(EXTGRP, ignoreCase = true) ->
                    group = line.substring(EXTGRP.length).trim().takeIf { it.isNotEmpty() }
                line.startsWith(EXTVLCOPT, ignoreCase = true) -> {
                    val option = line.substring(EXTVLCOPT.length)
                    val key = option.substringBefore('=').trim().lowercase()
                    if (key.isNotEmpty() && option.contains('=')) options[key] = option.substringAfter('=').trim()
                }
                line.startsWith("#") -> Unit
                else -> {
                    val url = if (baseUrl != null) UrlUtils.resolve(baseUrl, line) else line
                    val attributes = LinkedHashMap(info?.attributes.orEmpty()).apply { putAll(options) }
                    val logo = attributes["tvg-logo"]?.takeIf { it.isNotBlank() }
                        ?.let { if (baseUrl != null) UrlUtils.resolve(baseUrl, it) else it }
                    entries += M3uEntry(
                        name = info?.name?.takeIf { it.isNotBlank() }
                            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: UrlUtils.fileNameOf(url)
                            ?: url,
                        url = url,
                        logoUrl = logo,
                        group = attributes["group-title"]?.takeIf { it.isNotBlank() } ?: group,
                        durationSec = info?.duration?.takeIf { it > 0 },
                        attributes = attributes,
                    )
                    info = null
                    group = null
                    options.clear()
                }
            }
        }
        return entries
    }

    private class ExtInf(val duration: Double?, val attributes: Map<String, String>, val name: String)

    /**
     * Parses `-1 tvg-id="x" tvg-logo="http://..." group-title="News, Local",Channel Name`.
     * The title starts after the first comma that is outside a quoted attribute value.
     */
    private fun parseExtInf(value: String): ExtInf {
        var i = 0
        while (i < value.length && value[i].isWhitespace()) i++
        val durationStart = i
        while (i < value.length && !value[i].isWhitespace() && value[i] != ',') i++
        val duration = value.substring(durationStart, i).toDoubleOrNull()

        val attributes = LinkedHashMap<String, String>()
        while (i < value.length && value[i] != ',') {
            if (value[i].isWhitespace()) {
                i++
                continue
            }
            val keyStart = i
            while (i < value.length && value[i] != '=' && value[i] != ',' && !value[i].isWhitespace()) i++
            val key = value.substring(keyStart, i).lowercase()
            if (i >= value.length || value[i] != '=') continue
            i++ // '='
            val attrValue: String
            if (i < value.length && (value[i] == '"' || value[i] == '\'')) {
                val quote = value[i]
                val close = value.indexOf(quote, i + 1)
                if (close < 0) {
                    attrValue = value.substring(i + 1)
                    i = value.length
                } else {
                    attrValue = value.substring(i + 1, close)
                    i = close + 1
                }
            } else {
                val start = i
                while (i < value.length && value[i] != ',' && !value[i].isWhitespace()) i++
                attrValue = value.substring(start, i)
            }
            if (key.isNotEmpty()) attributes[key] = attrValue.trim()
        }
        val name = if (i < value.length) value.substring(i + 1).trim() else ""
        return ExtInf(duration, attributes, name)
    }
}
