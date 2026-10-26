package app.snapcast.ui.video

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.util.Formatters

/**
 * Kinds of metadata tags shown under a video in the list. [QUALITY_RANGE] is the best quality of a stream whose
 * player switches between several ("Up to 1080p"); [QUALITY] a fixed one (a file, one variant, a picked quality).
 */
enum class VideoChipKind { TYPE, QUALITY, QUALITY_RANGE, LIVE, DURATION, SIZE, SUBTITLES, DOMAIN, DRM }

/**
 * One tag. [text] is null for kinds whose label is a localized string chosen by the UI
 * ([VideoChipKind.LIVE], [VideoChipKind.DRM], [VideoChipKind.SUBTITLES]); for [VideoChipKind.QUALITY_RANGE]
 * it is the best quality, which the UI wraps ("Up to %s").
 */
data class VideoChip(val kind: VideoChipKind, val text: String?)

/** Pure formatting of the video list metadata (no Android types). */
object VideoLabels {
    /**
     * Tags in display order: type (HLS/DASH/MP4...), quality, LIVE or duration, size, subtitles, source domain, DRM.
     * Unknown values are omitted. [picked] is the quality chosen for this video, shown instead of the stream's range.
     */
    fun chips(video: DetectedVideo, picked: VideoVariant? = null): List<VideoChip> = buildList {
        add(VideoChip(VideoChipKind.TYPE, video.type.label))
        if (picked != null) {
            add(VideoChip(VideoChipKind.QUALITY, picked.qualityLabel))
        } else {
            video.qualityLabel?.takeIf { it.isNotBlank() }?.let {
                add(VideoChip(if (video.hasQualityRange) VideoChipKind.QUALITY_RANGE else VideoChipKind.QUALITY, it))
            }
        }
        if (video.isLive) {
            add(VideoChip(VideoChipKind.LIVE, null))
        } else {
            video.durationSec?.takeIf { it.isFinite() && it > 0.0 }?.let {
                add(VideoChip(VideoChipKind.DURATION, Formatters.duration(it)))
            }
        }
        // An adaptive stream's only known length is its playlist's, which says nothing about the video.
        video.sizeBytes?.takeIf { it > 0 && !video.type.isAdaptive }?.let { add(VideoChip(VideoChipKind.SIZE, Formatters.fileSize(it))) }
        if (video.subtitles.isNotEmpty()) add(VideoChip(VideoChipKind.SUBTITLES, null))
        video.sourceDomain.takeIf { it.isNotBlank() }?.let { add(VideoChip(VideoChipKind.DOMAIN, it)) }
        if (video.isDrmProtected) add(VideoChip(VideoChipKind.DRM, null))
    }

    /**
     * Variants worth offering in "Choose quality", best first and without duplicate labels. Only HLS variants
     * can be cast on their own: every DASH representation points at the manifest itself, and an HLS variant
     * whose audio is a separate rendition would play without sound (the master is cast instead).
     */
    fun qualityChoices(video: DetectedVideo): List<VideoVariant> {
        if (video.type == StreamType.DASH) return emptyList()
        return video.variants
            .filter { !it.separateAudio }
            .sortedWith(compareByDescending<VideoVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth ?: 0L })
            .distinctBy { it.qualityLabel to it.bandwidth }
    }

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
