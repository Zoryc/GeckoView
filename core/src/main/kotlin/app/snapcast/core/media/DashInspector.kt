package app.snapcast.core.media

import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.util.UrlUtils
import org.w3c.dom.Element

data class DashManifestInfo(
    /** MPD@type == "dynamic". */
    val isLive: Boolean,
    /** MPD@mediaPresentationDuration in seconds, if present. */
    val durationSec: Double?,
    /** Video representations (best first). URL of each is the manifest URL. */
    val representations: List<VideoVariant>,
    /** Non-null when any ContentProtection element is present. */
    val drm: DrmInfo?,
    /** Text AdaptationSets (mimeType text/vtt or contentType text) that expose a single BaseURL file. */
    val subtitles: List<SubtitleTrack>,
)

/** Reads the facts we need from an MPEG-DASH manifest without fully modelling it. */
object DashInspector {
    private const val DRM_REASON = "DASH ContentProtection"
    private const val MP4_PROTECTION_SCHEME = "urn:mpeg:dash:mp4protection:2011"
    private const val TRICK_MODE_SCHEME = "http://dashif.org/guidelines/trickmode"

    /** Known DRM system ids, in the order used to pick one when a manifest lists several. */
    private val DRM_SYSTEMS = linkedMapOf(
        "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" to "Widevine",
        "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95" to "PlayReady",
        "urn:uuid:79f0049a-4098-8642-ab92-e65be0885f95" to "PlayReady",
        "urn:uuid:94ce86fb-07ff-4f43-adb8-93d2fa968ca2" to "FairPlay",
        "urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e" to "ClearKey",
        "urn:uuid:1077efec-c0b2-4d02-ace3-3c1e52e2fb4b" to "ClearKey",
    )

    private val VIDEO_CODEC_PREFIXES = listOf("avc1", "avc3", "hvc1", "hev1", "vp08", "vp09", "vp8", "vp9", "av01", "dvh1", "dvhe")

    /** One ISO-8601 number: "3", "3.5", "3," or ".5" (comma allowed as decimal separator). */
    private const val ISO_NUMBER = "(\\d+(?:[.,]\\d*)?|[.,]\\d+)"
    private val ISO_DURATION = Regex(
        "^P(?:${ISO_NUMBER}Y)?(?:${ISO_NUMBER}M)?(?:${ISO_NUMBER}W)?(?:${ISO_NUMBER}D)?" +
            "(?:T(?:${ISO_NUMBER}H)?(?:${ISO_NUMBER}M)?(?:${ISO_NUMBER}S)?)?$",
    )

    // Same approximations as ExoPlayer for the calendar units an MPD may (rarely) use.
    private const val SECONDS_PER_YEAR = 31_556_908.8
    private const val SECONDS_PER_MONTH = 2_629_739.52

    /** Returns null when [xml] is not an MPD. Must be safe against XXE (no DTDs / external entities). */
    fun inspect(xml: String, manifestUrl: String): DashManifestInfo? {
        val root = SafeXml.parse(xml)?.documentElement ?: return null
        if (root.localTag != "MPD") return null

        val isLive = root.attr("type").equals("dynamic", ignoreCase = true)
        val periods = root.childElements("Period")
        val duration = parseIsoDuration(root.attr("mediaPresentationDuration"))
            ?: periods.map { parseIsoDuration(it.attr("duration")) }
                .takeIf { list -> list.isNotEmpty() && list.all { it != null } }
                ?.sumOf { it!! }

        val mpdBase = baseUrlOf(root, manifestUrl)
        val representations = mutableListOf<VideoVariant>()
        val subtitles = mutableListOf<SubtitleTrack>()
        for (period in periods) {
            val periodBase = baseUrlOf(period, mpdBase)
            for (set in period.childElements("AdaptationSet")) {
                if (isTrickMode(set)) continue
                val setBase = baseUrlOf(set, periodBase)
                for (rep in set.childElements("Representation")) {
                    when {
                        isVideo(set, rep) -> representations += videoVariant(set, rep, manifestUrl)
                        isText(set, rep) -> subtitleTrack(set, rep, setBase)?.let { subtitles += it }
                    }
                }
            }
        }

        return DashManifestInfo(
            isLive = isLive,
            durationSec = duration,
            representations = representations
                .sortedWith(compareByDescending<VideoVariant> { it.height ?: -1 }.thenByDescending { it.bandwidth ?: -1L })
                .distinctBy { Triple(it.width, it.height, it.bandwidth) },
            drm = detectDrm(root),
            subtitles = subtitles.distinctBy { it.id },
        )
    }

    private fun baseUrlOf(element: Element, parentBase: String): String {
        val base = element.childElements("BaseURL").firstOrNull()?.textContent?.trim()
        return if (base.isNullOrEmpty()) parentBase else UrlUtils.resolve(parentBase, base)
    }

    private fun isTrickMode(set: Element): Boolean =
        (set.childElements("EssentialProperty") + set.childElements("SupplementalProperty"))
            .any { it.attr("schemeIdUri").equals(TRICK_MODE_SCHEME, ignoreCase = true) }

    private fun mimeOf(set: Element, rep: Element): String? = (rep.attr("mimeType") ?: set.attr("mimeType"))?.lowercase()

    private fun contentTypeOf(set: Element): String? =
        (set.attr("contentType") ?: set.childElements("ContentComponent").firstNotNullOfOrNull { it.attr("contentType") })
            ?.lowercase()

    private fun isVideo(set: Element, rep: Element): Boolean {
        val mime = mimeOf(set, rep)
        if (mime != null && !mime.startsWith("application/")) return mime.startsWith("video/")
        val contentType = contentTypeOf(set)
        if (contentType != null) return contentType == "video"
        val codecs = (rep.attr("codecs") ?: set.attr("codecs"))?.lowercase()
        return mime == null && codecs != null && VIDEO_CODEC_PREFIXES.any { codecs.startsWith(it) }
    }

    private fun isText(set: Element, rep: Element): Boolean {
        val mime = mimeOf(set, rep)
        return contentTypeOf(set) == "text" ||
            mime?.startsWith("text/") == true ||
            mime == "application/x-subrip" ||
            mime == "application/ttml+xml"
    }

    private fun videoVariant(set: Element, rep: Element, manifestUrl: String): VideoVariant = VideoVariant(
        url = manifestUrl,
        bandwidth = rep.attr("bandwidth")?.toLongOrNull(),
        width = (rep.attr("width") ?: set.attr("width"))?.toIntOrNull(),
        height = (rep.attr("height") ?: set.attr("height"))?.toIntOrNull(),
        codecs = rep.attr("codecs") ?: set.attr("codecs"),
        frameRate = parseFrameRate(rep.attr("frameRate") ?: set.attr("frameRate")),
    )

    /** "25", "29.97" or "30000/1001". */
    private fun parseFrameRate(value: String?): Double? {
        if (value == null) return null
        val parts = value.split('/')
        val rate = if (parts.size == 2) {
            val num = parts[0].trim().toDoubleOrNull()
            val den = parts[1].trim().toDoubleOrNull()
            if (num == null || den == null || den == 0.0) null else num / den
        } else {
            value.trim().toDoubleOrNull()
        }
        return rate?.takeIf { it.isFinite() && it > 0 }
    }

    private fun subtitleTrack(set: Element, rep: Element, setBase: String): SubtitleTrack? {
        val base = rep.childElements("BaseURL").firstOrNull()?.textContent?.trim()
        if (base.isNullOrEmpty()) return null
        val url = UrlUtils.resolve(setBase, base)
        val format = MediaClassifier.subtitleFormatFromUrl(url) ?: return null
        val label = set.childElements("Label").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            ?: set.attr("label")
            ?: rep.childElements("Label").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            ?: rep.attr("label")
        return SubtitleTrack(
            id = UrlUtils.stableId(url),
            url = url,
            label = label,
            language = set.attr("lang") ?: rep.attr("lang"),
            format = format,
        )
    }

    private fun detectDrm(root: Element): DrmInfo? {
        val schemes = root.descendants("ContentProtection").map { it.attr("schemeIdUri")?.lowercase().orEmpty() }
        if (schemes.isEmpty()) return null
        val system = DRM_SYSTEMS.entries.firstOrNull { it.key in schemes }?.value
            ?: if (MP4_PROTECTION_SCHEME in schemes) "CENC" else schemes.firstOrNull { it.isNotEmpty() }
        return DrmInfo(system = system, reason = DRM_REASON)
    }

    /** Parses an ISO-8601 duration such as "PT1H2M3.5S" or "P1DT2H" into seconds, or null. */
    fun parseIsoDuration(value: String?): Double? {
        val text = value?.trim()?.uppercase()?.takeIf { it.length > 1 } ?: return null
        val match = ISO_DURATION.matchEntire(text) ?: return null
        val groups = match.groupValues
        if (groups.drop(1).all { it.isEmpty() }) return null
        fun part(index: Int): Double = groups[index].replace(',', '.').toDoubleOrNull() ?: 0.0
        return part(1) * SECONDS_PER_YEAR +
            part(2) * SECONDS_PER_MONTH +
            part(3) * 7 * 86_400 +
            part(4) * 86_400 +
            part(5) * 3_600 +
            part(6) * 60 +
            part(7)
    }
}
