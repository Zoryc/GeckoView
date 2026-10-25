package app.snapcast.ui.video

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.util.Formatters

/** Kinds of metadata tags shown under a video in the list. */
enum class VideoChipKind { TYPE, QUALITY, LIVE, DURATION, SIZE, SUBTITLES, DOMAIN, DRM }

/**
 * One tag. [text] is null for kinds whose label is a localized string chosen by the UI
 * ([VideoChipKind.LIVE], [VideoChipKind.DRM], [VideoChipKind.SUBTITLES]).
 */
data class VideoChip(val kind: VideoChipKind, val text: String?)

/** Pure formatting of the video list metadata (no Android types). */
object VideoLabels {
    /**
     * Tags in display order: type (HLS/DASH/MP4...), quality, LIVE or duration, size, subtitles, source domain, DRM.
     * Unknown values are omitted.
     */
    fun chips(video: DetectedVideo): List<VideoChip> = buildList {
        add(VideoChip(VideoChipKind.TYPE, video.type.label))
        video.qualityLabel?.takeIf { it.isNotBlank() }?.let { add(VideoChip(VideoChipKind.QUALITY, it)) }
        if (video.isLive) {
            add(VideoChip(VideoChipKind.LIVE, null))
        } else {
            video.durationSec?.takeIf { it.isFinite() && it > 0.0 }?.let {
                add(VideoChip(VideoChipKind.DURATION, Formatters.duration(it)))
            }
        }
        video.sizeBytes?.takeIf { it > 0 }?.let { add(VideoChip(VideoChipKind.SIZE, Formatters.fileSize(it))) }
        if (video.subtitles.isNotEmpty()) add(VideoChip(VideoChipKind.SUBTITLES, null))
        video.sourceDomain.takeIf { it.isNotBlank() }?.let { add(VideoChip(VideoChipKind.DOMAIN, it)) }
        if (video.isDrmProtected) add(VideoChip(VideoChipKind.DRM, null))
    }

    /** Variants worth offering in "Choose quality", best first and without duplicate labels. */
    fun qualityChoices(video: DetectedVideo): List<VideoVariant> =
        video.variants
            .sortedWith(compareByDescending<VideoVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth ?: 0L })
            .distinctBy { it.qualityLabel to it.bandwidth }

    /** Secondary line of a quality choice, e.g. "2.5 Mbps · avc1.64001f"; null when nothing is known. */
    fun variantDetails(variant: VideoVariant): String? {
        val parts = buildList {
            variant.bandwidth?.takeIf { it > 0 }?.let { add(VideoVariant.formatBitrate(it)) }
            val width = variant.width
            val height = variant.height
            if (width != null && height != null && width > 0 && height > 0) add("$width×$height")
            variant.codecs?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
