package app.snapcast.player.media

import androidx.media3.common.MimeTypes
import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import java.util.Locale

/** MIME types handed to Media3 so it picks the right media source and subtitle parser. */
internal object PlayerMimeTypes {

    /**
     * MIME type of the main media item.
     *
     * HLS and DASH get Media3's manifest types, which select the adaptive media sources. A specific
     * HLS/DASH Content-Type also wins over a progressive [type], since some servers hide manifests behind
     * extension-less URLs. Progressive streams keep the server's concrete `video/…` or `audio/…` type, or
     * null so ExoPlayer sniffs the container itself.
     */
    fun forStream(type: StreamType, mimeType: String?): String? {
        val effective = if (type.isAdaptive) {
            type
        } else {
            MediaClassifier.typeFromContentType(mimeType)?.takeIf { it.isAdaptive } ?: type
        }
        return when (effective) {
            StreamType.HLS -> MimeTypes.APPLICATION_M3U8
            StreamType.DASH -> MimeTypes.APPLICATION_MPD
            else -> progressive(mimeType)
        }
    }

    /**
     * MIME type of a side-loaded subtitle track. An unknown format is inferred from the URL extension and
     * otherwise assumed to be WebVTT, the only format `<track>` elements support in browsers.
     */
    fun forSubtitle(track: SubtitleTrack): String {
        val format = track.format.takeIf { it != SubtitleFormat.UNKNOWN }
            ?: MediaClassifier.subtitleFormatFromUrl(track.url)
            ?: SubtitleFormat.VTT
        return when (format) {
            SubtitleFormat.VTT, SubtitleFormat.UNKNOWN -> MimeTypes.TEXT_VTT
            SubtitleFormat.SRT -> MimeTypes.APPLICATION_SUBRIP
            SubtitleFormat.TTML -> MimeTypes.APPLICATION_TTML
        }
    }

    /** Concrete audio/video MIME type without parameters, or null for generic / wildcard / non-media types. */
    private fun progressive(mimeType: String?): String? {
        val base = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        val subtype = when {
            base.startsWith(VIDEO_PREFIX) -> base.removePrefix(VIDEO_PREFIX)
            base.startsWith(AUDIO_PREFIX) -> base.removePrefix(AUDIO_PREFIX)
            else -> return null
        }
        return base.takeIf { subtype.isNotEmpty() && '*' !in subtype }
    }

    private const val VIDEO_PREFIX = "video/"
    private const val AUDIO_PREFIX = "audio/"
}
