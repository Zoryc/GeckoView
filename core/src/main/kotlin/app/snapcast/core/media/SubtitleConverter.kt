package app.snapcast.core.media

import app.snapcast.core.model.SubtitleFormat
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.Locale

/** Subtitle helpers. Cast receivers only understand WebVTT (and TTML), so SRT is converted. */
object SubtitleConverter {
    private const val VTT_HEADER = "WEBVTT"

    private const val TIMESTAMP = "(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})(?:[,.](\\d{1,3}))?"
    private val TIMING_LINE = Regex("^\\s*$TIMESTAMP\\s*-->\\s*$TIMESTAMP(.*)$")
    private val SRT_START = Regex("^(?:\\d+\\s*\\n)?\\s*$TIMESTAMP\\s*-->")
    private val TTML_ROOT = Regex("^<(?:[A-Za-z_][\\w.\\-]*:)?tt(?:[\\s>/:]|$)")
    private val LINE_BREAK = Regex("\r\n|\r|\n")
    private val NUMERIC = Regex("\\d+")

    /** SSA override blocks such as {\an8} or {\i1}. */
    private val SSA_TAG = Regex("\\{\\\\[^}]*\\}")

    /** Any tag except b/i/u (which WebVTT supports). */
    private val UNSUPPORTED_TAG = Regex("</?(?![biuBIU]\\s*>)[A-Za-z][^>]*>")
    private val SUPPORTED_TAG = Regex("<(/?)([biuBIU])\\s*>")
    private val STRAY_LT = Regex("<(?!/?[biu]>)")
    private val BARE_AMP = Regex("&(?!(?:[A-Za-z][A-Za-z0-9]*|#\\d+|#[xX][0-9A-Fa-f]+);)")

    /** Detects VTT ("WEBVTT" header) or SRT ("1\n00:00:01,000 --> ...") from content; UNKNOWN otherwise. TTML is recognised too. */
    fun sniffFormat(text: String): SubtitleFormat {
        val body = normalize(text).trimStart()
        if (body.startsWith(VTT_HEADER) && (body.length == VTT_HEADER.length || body[VTT_HEADER.length].isWhitespace())) {
            return SubtitleFormat.VTT
        }
        if (SRT_START.containsMatchIn(body)) return SubtitleFormat.SRT
        if (TTML_ROOT.containsMatchIn(skipXmlProlog(body))) return SubtitleFormat.TTML
        return SubtitleFormat.UNKNOWN
    }

    /**
     * Converts SubRip to WebVTT: strips BOM, normalizes line endings, adds the WEBVTT header,
     * converts "," decimal separators in timestamps to ".", pads hour-less timestamps, removes
     * unsupported formatting ({\an8}, <font ...>) while keeping <b>/<i>/<u>.
     */
    fun srtToVtt(srt: String): String {
        val cues = mutableListOf<Cue>()
        var current: Cue? = null
        // Lines seen since the last timing line: text of the current cue, possibly ending with the next cue's index.
        val buffer = mutableListOf<String>()
        for (line in normalize(srt).split('\n')) {
            val timing = TIMING_LINE.find(line)
            if (timing == null) {
                buffer += line
                continue
            }
            // The numeric line right before a timing line is that cue's index, not text of the previous cue.
            val id = buffer.lastOrNull()?.trim()?.takeIf { NUMERIC.matches(it) }
            if (id != null) buffer.removeAt(buffer.lastIndex)
            current?.lines?.addAll(buffer)
            buffer.clear()
            val cue = Cue(id, vttTimestamp(timing.groupValues, 1), vttTimestamp(timing.groupValues, 5))
            cues += cue
            current = cue
        }
        current?.lines?.addAll(buffer)
        return buildString {
            append(VTT_HEADER).append('\n')
            for (cue in cues) {
                val text = cue.lines.map(::cleanCueText).filter { it.isNotBlank() }
                if (text.isEmpty()) continue
                append('\n')
                cue.id?.let { append(it).append('\n') }
                append(cue.start).append(" --> ").append(cue.end).append('\n')
                text.forEach { append(it).append('\n') }
            }
        }
    }

    /** Returns WebVTT for [text] whatever its format (VTT passes through, SRT and TTML are converted). */
    fun toVtt(text: String, format: SubtitleFormat = sniffFormat(text)): String = when (format) {
        SubtitleFormat.VTT ->
            if (normalize(text).trimStart().startsWith(VTT_HEADER)) text else "$VTT_HEADER\n\n${normalize(text)}"
        SubtitleFormat.SRT -> srtToVtt(text)
        SubtitleFormat.TTML -> ttmlToVtt(text) ?: "$VTT_HEADER\n"
        SubtitleFormat.UNKNOWN -> when (val sniffed = sniffFormat(text)) {
            SubtitleFormat.UNKNOWN -> srtToVtt(text)
            else -> toVtt(text, sniffed)
        }
    }

    private class Cue(val id: String?, val start: String, val end: String) {
        val lines = mutableListOf<String>()
    }

    private fun normalize(text: String): String = text.removePrefix("﻿").replace(LINE_BREAK, "\n")

    /** Builds "HH:MM:SS.mmm" from the 4 timestamp groups starting at [first] (hours, minutes, seconds, fraction). */
    private fun vttTimestamp(groups: List<String>, first: Int): String {
        val hours = groups[first].toLongOrNull() ?: 0L
        val minutes = groups[first + 1].toLong()
        val seconds = groups[first + 2].toLong()
        val millis = groups[first + 3].padEnd(3, '0').toLong()
        return formatTimestamp(((hours * 3600 + minutes * 60 + seconds) * 1000) + millis)
    }

    private fun formatTimestamp(totalMillis: Long): String {
        val ms = totalMillis.coerceAtLeast(0)
        return String.format(
            Locale.US,
            "%02d:%02d:%02d.%03d",
            ms / 3_600_000,
            (ms / 60_000) % 60,
            (ms / 1000) % 60,
            ms % 1000,
        )
    }

    private fun cleanCueText(line: String): String {
        var text = line.replace(SSA_TAG, "")
        text = text.replace(UNSUPPORTED_TAG, "")
        text = text.replace(SUPPORTED_TAG) { "<${it.groupValues[1]}${it.groupValues[2].lowercase()}>" }
        text = text.replace(BARE_AMP, "&amp;")
        text = text.replace(STRAY_LT, "&lt;")
        // "-->" inside cue text would be read as a timing line.
        return text.replace("-->", "->").trimEnd()
    }

    private fun skipXmlProlog(text: String): String {
        var rest = text
        while (true) {
            rest = when {
                rest.startsWith("<?") -> rest.substringAfter("?>", "").trimStart()
                rest.startsWith("<!--") -> rest.substringAfter("-->", "").trimStart()
                else -> return rest
            }
        }
    }

    // ---- TTML / DFXP ----

    private val CLOCK_TIME = Regex("^(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)$")
    private val CLOCK_TIME_FRAMES = Regex("^(\\d+):(\\d{2}):(\\d{2}):(\\d+(?:\\.\\d+)?)$")
    private val OFFSET_TIME = Regex("^(\\d+(?:\\.\\d+)?)(h|m|s|ms|f|t)$")
    private val WHITESPACE = Regex("\\s+")

    private class TimingContext(val frameRate: Double, val tickRate: Double)

    /** Converts the timed `<p>` paragraphs of a TTML document to WebVTT, or null when it is not TTML. */
    private fun ttmlToVtt(xml: String): String? {
        val root = SafeXml.parse(xml)?.documentElement ?: return null
        if (root.localTag != "tt") return null
        val frameRate = root.attr("frameRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 30.0
        val multiplier = root.attr("frameRateMultiplier")?.split(' ')
            ?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 2 && it[1] != 0.0 }
            ?.let { it[0] / it[1] } ?: 1.0
        val effectiveFrameRate = frameRate * multiplier
        val tickRate = root.attr("tickRate")?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: if (root.attr("frameRate") != null) effectiveFrameRate else 1.0
        val context = TimingContext(effectiveFrameRate, tickRate)

        data class TtmlCue(val start: Long, val end: Long, val text: String)
        val cues = root.descendants("p").mapNotNull { p ->
            val begin = parseTtmlTime(p.attr("begin"), context) ?: return@mapNotNull null
            val end = parseTtmlTime(p.attr("end"), context)
                ?: parseTtmlTime(p.attr("dur"), context)?.let { begin + it }
                ?: return@mapNotNull null
            val text = paragraphText(p)
            if (text.isBlank() || end <= begin) null else TtmlCue(begin, end, text)
        }.sortedBy { it.start }

        return buildString {
            append(VTT_HEADER).append('\n')
            for (cue in cues) {
                append('\n')
                append(formatTimestamp(cue.start)).append(" --> ").append(formatTimestamp(cue.end)).append('\n')
                append(cue.text).append('\n')
            }
        }
    }

    private fun parseTtmlTime(value: String?, context: TimingContext): Long? {
        val v = value?.trim() ?: return null
        CLOCK_TIME.matchEntire(v)?.let { m ->
            val (h, min, s) = m.destructured
            return Math.round((h.toLong() * 3600 + min.toLong() * 60 + s.toDouble()) * 1000)
        }
        CLOCK_TIME_FRAMES.matchEntire(v)?.let { m ->
            val (h, min, s, f) = m.destructured
            return Math.round((h.toLong() * 3600 + min.toLong() * 60 + s.toLong() + f.toDouble() / context.frameRate) * 1000)
        }
        OFFSET_TIME.matchEntire(v)?.let { m ->
            val amount = m.groupValues[1].toDouble()
            val seconds = when (m.groupValues[2]) {
                "h" -> amount * 3600
                "m" -> amount * 60
                "s" -> amount
                "ms" -> amount / 1000
                "f" -> amount / context.frameRate
                else -> amount / context.tickRate
            }
            return Math.round(seconds * 1000)
        }
        return null
    }

    /** Text of a paragraph: whitespace collapsed, `<br/>` as line breaks, WebVTT special characters escaped. */
    private fun paragraphText(p: Element): String {
        val out = StringBuilder()
        fun visit(node: Node) {
            val children = node.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                when (child.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> out.append(child.nodeValue.replace(WHITESPACE, " "))
                    Node.ELEMENT_NODE -> if ((child as Element).localTag == "br") out.append('\n') else visit(child)
                }
            }
        }
        visit(p)
        return out.toString()
            .split('\n')
            .map { it.trim().replace("&", "&amp;").replace("<", "&lt;").replace("-->", "->") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
