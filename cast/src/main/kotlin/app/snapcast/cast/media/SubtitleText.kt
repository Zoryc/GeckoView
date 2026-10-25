package app.snapcast.cast.media

import app.snapcast.core.model.SubtitleFormat
import java.nio.charset.Charset

/** Decoding of user-picked subtitle files. */
internal object SubtitleText {
    /** Files larger than this are not subtitles worth loading. */
    const val MAX_BYTES = 5 * 1024 * 1024

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    /** UTF-8 (without BOM), or Windows-1252 when the bytes are not valid UTF-8 (common for old .srt files). */
    fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        return if ('�' in utf8) String(bytes, WINDOWS_1252) else utf8
    }

    /** Format implied by a file name's extension, or [SubtitleFormat.UNKNOWN]. */
    fun formatFromFileName(fileName: String?): SubtitleFormat =
        when (fileName?.substringAfterLast('.', "")?.lowercase()) {
            "srt" -> SubtitleFormat.SRT
            "vtt", "webvtt" -> SubtitleFormat.VTT
            else -> SubtitleFormat.UNKNOWN
        }

    /** Track name for a picked file: its name without extension, or [fallback]. */
    fun trackName(fileName: String?, fallback: String): String =
        fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: fallback
}
