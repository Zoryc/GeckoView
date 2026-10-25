package app.snapcast.core.media

import app.snapcast.core.model.SubtitleFormat

/** Subtitle helpers. Cast receivers only understand WebVTT (and TTML), so SRT is converted. */
object SubtitleConverter {
    /** Detects VTT ("WEBVTT" header) or SRT ("1\n00:00:01,000 --> ...") from content; UNKNOWN otherwise. */
    fun sniffFormat(text: String): SubtitleFormat = TODO()

    /**
     * Converts SubRip to WebVTT: strips BOM, normalizes line endings, adds the WEBVTT header,
     * converts "," decimal separators in timestamps to ".", pads hour-less timestamps, removes
     * unsupported formatting ({\an8}, <font ...>) while keeping <b>/<i>/<u>.
     */
    fun srtToVtt(srt: String): String = TODO()

    /** Returns WebVTT for [text] whatever its format (VTT passes through, SRT is converted). */
    fun toVtt(text: String, format: SubtitleFormat = sniffFormat(text)): String = TODO()
}
