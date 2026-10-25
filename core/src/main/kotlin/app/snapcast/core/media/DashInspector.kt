package app.snapcast.core.media

import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant

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
    /** Returns null when [xml] is not an MPD. Must be safe against XXE (no DTDs / external entities). */
    fun inspect(xml: String, manifestUrl: String): DashManifestInfo? = TODO()

    /** Parses an ISO-8601 duration such as "PT1H2M3.5S" or "P1DT2H" into seconds, or null. */
    fun parseIsoDuration(value: String?): Double? = TODO()
}
