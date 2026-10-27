package app.snapcast.core.report

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DetectionSource
import app.snapcast.core.util.Formatters
import app.snapcast.core.util.UrlUtils
import java.net.URLEncoder

/** What the user says went wrong with a site ([label] is the English wording used in the GitHub issue). */
enum class ProblemKind(val label: String) {
    NO_VIDEO("Video not found"),
    WONT_PLAY("Video won't play"),
    CAST_FAILS("Casting fails"),
    NO_THUMBNAIL("No thumbnail"),
    WRONG_QUALITY("Wrong quality"),
    OTHER("Something else"),
}

/** The app and device a report comes from. */
data class ReportEnvironment(
    val appVersion: String,
    val engineVersion: String,
    val androidVersion: String,
    val device: String,
)

/** A recent failure, already reduced to hosts and status codes (see the diagnostics log). */
data class ReportEvent(val atMs: Long, val source: String, val message: String)

/**
 * A problem report about one site, written for a GitHub issue. It only says what the user sees and what the
 * app found, never anything that identifies them or their session: the page's address is reduced to its host
 * unless [includePageUrl], videos are described by type, quality and host (never their links), and only the
 * presence of a Referer, cookies or Authorization is mentioned, never their values.
 */
data class SiteReport(
    val pageUrl: String,
    val kind: ProblemKind,
    val details: String,
    val includePageUrl: Boolean,
    val environment: ReportEnvironment,
    val videos: List<DetectedVideo>,
    val events: List<ReportEvent>,
) {
    val siteHost: String get() = UrlUtils.hostOf(pageUrl)?.removePrefix("www.") ?: "unknown site"

    /** The page as it appears in the report: the full address only when the user allowed it. */
    val site: String
        get() = if (includePageUrl && UrlUtils.isHttp(pageUrl)) pageUrl.substringBefore('#') else siteHost

    val title: String get() = "$siteHost: ${kind.label}"

    /** Environment, videos and recent failures as Markdown (what the user previews before sending). */
    val diagnostics: String
        get() = buildString {
            with(environment) { append("- App: Snapcast $appVersion (GeckoView $engineVersion), Android $androidVersion, $device\n") }
            if (videos.isEmpty()) {
                append("- Videos found on the page: none\n")
            } else {
                append("- Videos found on the page: ${videos.size}\n")
                videos.take(MAX_VIDEOS).forEachIndexed { index, video -> append("  ${index + 1}. ${describe(video)}\n") }
                if (videos.size > MAX_VIDEOS) append("  … and ${videos.size - MAX_VIDEOS} more\n")
            }
            if (events.isNotEmpty()) {
                append("- Recent problems:\n")
                events.takeLast(MAX_EVENTS).forEach { append("  - ${it.source}: ${it.message}\n") }
            }
        }.trimEnd()

    /** The whole report as Markdown, for sharing. */
    val body: String
        get() = buildString {
            append("### Site\n").append(site).append("\n\n")
            append("### Problem\n").append(kind.label).append("\n\n")
            if (details.isNotBlank()) append("### What happened\n").append(details.trim()).append("\n\n")
            append("### Diagnostics\n").append(diagnostics).append('\n')
        }

    /**
     * A link to a new issue in [repository] ("owner/name") with the report's form filled in, at most
     * [MAX_ISSUE_URL] characters once encoded (GitHub refuses longer links). A page address longer than
     * [MAX_LINKED_ADDRESS] gives way to the host; the details and the diagnostics then share the room left (half
     * each, unless one needs less) and are shortened to fit. Sharing gives the full text.
     */
    fun issueUrl(repository: String): String {
        val base = "https://github.com/$repository/issues/new?"
        val siteText = if (encode(site).length > MAX_LINKED_ADDRESS) siteHost else site
        var detailsText = details.trim()
        var diagnosticsText = diagnostics
        fun build(): String = base + linkedMapOf(
            "template" to ISSUE_TEMPLATE,
            "title" to title,
            "site" to siteText,
            "problem" to kind.label,
            "details" to detailsText,
            "diagnostics" to diagnosticsText,
        ).entries.joinToString("&") { (name, value) -> "$name=${encode(value)}" }

        val url = build()
        if (url.length <= MAX_ISSUE_URL) return url
        val detailsSize = encode(detailsText).length
        val diagnosticsSize = encode(diagnosticsText).length
        val room = (MAX_ISSUE_URL - (url.length - detailsSize - diagnosticsSize)).coerceAtLeast(0)
        val detailsRoom = maxOf(room / 2, room - diagnosticsSize)
        val diagnosticsRoom = room - minOf(detailsSize, detailsRoom)
        if (detailsSize > detailsRoom) detailsText = shortened(detailsText, detailsSize - detailsRoom)
        if (diagnosticsSize > diagnosticsRoom) diagnosticsText = shortened(diagnosticsText, diagnosticsSize - diagnosticsRoom)
        return build()
    }

    private fun describe(video: DetectedVideo): String {
        val parts = ArrayList<String>()
        parts += video.type.label
        video.qualityLabel?.let { label ->
            parts += if (video.hasQualityRange) "up to $label (${video.variants.size} qualities)" else label
        }
        if (video.isLive) parts += "live" else video.durationSec?.takeIf { it.isFinite() && it > 0 }?.let { parts += Formatters.duration(it) }
        val found = when (video.source) {
            DetectionSource.NETWORK -> "network"
            DetectionSource.DOM -> "page only"
            DetectionSource.BOTH -> "network + page"
        }
        parts += "from ${video.sourceDomain.ifBlank { "?" }} ($found)"
        parts += when {
            video.posterUrl != null -> "poster from ${UrlUtils.hostOf(video.posterUrl)?.removePrefix("www.") ?: "?"}"
            video.frameThumbnail != null -> "frame thumbnail"
            else -> "no picture"
        }
        val sent = listOfNotNull(
            "referer".takeIf { !video.headers.referer.isNullOrBlank() },
            "cookies".takeIf { !video.headers.cookie.isNullOrBlank() || video.cookiesByHost.isNotEmpty() },
            "authorization".takeIf { video.headers.extra.keys.any { it.equals("Authorization", ignoreCase = true) } },
        )
        parts += if (sent.isEmpty()) "no headers captured" else "captured: ${sent.joinToString(", ")}"
        if (video.isDrmProtected) parts += "DRM (${video.drm?.system ?: video.drm?.reason})"
        return parts.joinToString(" · ")
    }

    companion object {
        const val ISSUE_TEMPLATE = "site-problem.yml"

        /** GitHub answers 414 above about 8 KB of request line; this leaves room for the rest of it. */
        const val MAX_ISSUE_URL = 7_500

        /** Encoded length above which the page address (when shared) is replaced by its host in the issue link. */
        const val MAX_LINKED_ADDRESS = 2_000
        private const val MAX_VIDEOS = 10
        private const val MAX_EVENTS = 8
        private const val SHORTENED = "\n…(shortened, share the report for the full text)"

        // The (String, String) overload: URLEncoder.encode(String, Charset) only exists from Android 13.
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /**
         * [text] shortened so its encoded form loses at least [excess] characters (the note saying so included),
         * never splitting a surrogate pair; empty when nothing useful is left.
         */
        private fun shortened(text: String, excess: Int): String {
            var keep = text.length
            var saved = 0
            val target = excess + encode(SHORTENED).length
            while (keep > 0 && saved < target) {
                // One code point at a time from the end (a surrogate pair is never split).
                val start = if (keep > 1 && Character.isLowSurrogate(text[keep - 1]) && Character.isHighSurrogate(text[keep - 2])) keep - 2 else keep - 1
                saved += encode(text.substring(start, keep)).length
                keep = start
            }
            return if (keep <= 0) "" else text.substring(0, keep) + SHORTENED
        }
    }
}
