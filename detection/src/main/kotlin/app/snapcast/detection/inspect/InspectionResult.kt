package app.snapcast.detection.inspect

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant

/**
 * Facts learned by fetching a detected stream (playlist, manifest or the first byte of a file).
 * Null / empty fields are unknown and leave the video as it is.
 */
internal data class InspectionResult(
    val isLive: Boolean? = null,
    val durationSec: Double? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val variants: List<VideoVariant> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val drm: DrmInfo? = null,
    val isMasterPlaylist: Boolean? = null,
    val hlsFragmentedMp4: Boolean? = null,
    /** Variant and rendition playlist URLs of an HLS master; the collector folds them into the master. */
    val childPlaylistUrls: List<String> = emptyList(),
) {
    /**
     * [video] updated with these facts: known values replace unknown ones, nothing already known is
     * lost (an existing DRM signal or MIME type wins), and live streams have no duration.
     */
    fun applyTo(video: DetectedVideo): DetectedVideo {
        val live = isLive ?: video.isLive
        return video.copy(
            isLive = live,
            durationSec = if (live) null else durationSec?.takeIf { it.isFinite() && it > 0 } ?: video.durationSec,
            sizeBytes = sizeBytes?.takeIf { it > 0 } ?: video.sizeBytes,
            mimeType = video.mimeType ?: mimeType,
            variants = variants.ifEmpty { video.variants },
            subtitles = mergeSubtitles(video.subtitles, subtitles),
            drm = video.drm ?: drm,
            isMasterPlaylist = isMasterPlaylist ?: video.isMasterPlaylist,
            hlsFragmentedMp4 = hlsFragmentedMp4 ?: video.hlsFragmentedMp4,
        )
    }

    private fun mergeSubtitles(existing: List<SubtitleTrack>, found: List<SubtitleTrack>): List<SubtitleTrack> {
        if (found.isEmpty()) return existing
        val ids = existing.mapTo(HashSet()) { it.id }
        val urls = existing.mapTo(HashSet()) { it.url }
        return existing + found.filter { it.id !in ids && it.url !in urls }.distinctBy { it.id }
    }
}
