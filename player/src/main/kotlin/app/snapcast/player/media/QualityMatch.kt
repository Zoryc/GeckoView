package app.snapcast.player.media

import app.snapcast.core.model.VideoResolution
import app.snapcast.player.PlaybackQuality
import kotlin.math.abs

/** Finds the rendition of an adaptive stream that matches the quality picked in "Choose quality". */
internal object QualityMatch {
    /** One video rendition as the player sees it; non-positive values are unknown. */
    data class Rendition(val width: Int, val height: Int, val bitrate: Int)

    /**
     * Index of the rendition closest to [wanted]: same resolution class first (1080p, 720p...), then the
     * nearest bitrate. Null when there is nothing to choose from or [wanted] describes nothing.
     */
    fun pick(renditions: List<Rendition>, wanted: PlaybackQuality): Int? {
        val wantedClass = VideoResolution.classOf(wanted.width, wanted.height)
        val wantedBitrate = wanted.bandwidth?.takeIf { it > 0 }
        if (renditions.isEmpty() || (wantedClass == null && wantedBitrate == null)) return null
        val byResolution = compareBy<Int> { index ->
            val rendition = renditions[index]
            val renditionClass = VideoResolution.classOf(rendition.width.takeIf { it > 0 }, rendition.height.takeIf { it > 0 })
            if (wantedClass == null) 0 else renditionClass?.let { abs(it - wantedClass) } ?: Int.MAX_VALUE
        }
        val byBitrate = compareBy<Int> { index ->
            val bitrate = renditions[index].bitrate.takeIf { it > 0 }?.toLong()
            if (wantedBitrate == null) 0L else bitrate?.let { abs(it - wantedBitrate) } ?: Long.MAX_VALUE
        }
        return renditions.indices.minWithOrNull(byResolution.then(byBitrate))
    }
}
