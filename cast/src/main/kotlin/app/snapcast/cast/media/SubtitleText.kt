package app.snapcast.cast.media

import app.snapcast.core.model.SubtitleFormat
import java.nio.charset.Charset

/** Decoding of user-picked subtitle files. */
internal object SubtitleText {
    /** Files larger than this are not subtitles worth loading. */
    const val MAX_BYTES = 5 * 1024 * 1024

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
    private const val BOM = "\uFEFF"
    private const val REPLACEMENT = '\uFFFD'

    /**
     * UTF-16 when the bytes start with a UTF-16 byte order mark ("Unicode" in Windows Notepad and subtitle
     * editors), else UTF-8 (BOM removed), else Windows-1252 when the bytes are not valid UTF-8 (common for
     * old .srt files).
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8).removePrefix(BOM)
        return if (REPLACEMENT in utf8) String(bytes, WINDOWS_1252) else utf8
    }

    /** Format implied by a file name's extension, or [SubtitleFormat.UNKNOWN]. */
    fun formatFromFileName(fileName: String?): SubtitleFormat =
        when (fileName?.substringAfterLast('.', "")?.lowercase()) {
            "srt" -> SubtitleFormat.SRT
            "vtt", "webvtt" -> SubtitleFormat.VTT
            "ttml", "dfxp" -> SubtitleFormat.TTML
            else -> SubtitleFormat.UNKNOWN
        }

    /** Track name for a picked file: its name without extension, or [fallback]. */
    fun trackName(fileName: String?, fallback: String): String =
        fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: fallback
}
