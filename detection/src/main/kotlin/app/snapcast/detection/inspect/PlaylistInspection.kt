package app.snapcast.detection.inspect

import app.snapcast.core.media.DashManifestInfo
import app.snapcast.core.media.HlsParser
import app.snapcast.core.media.HlsPlaylist
import app.snapcast.core.util.UrlUtils

/** Pure mapping of parsed HLS playlists / DASH manifests to [InspectionResult]s. */
internal object PlaylistInspection {
    /** Segment extensions that mean fragmented MP4 (CMAF) even without an EXT-X-MAP tag. */
    private val FRAGMENTED_MP4_EXTENSIONS = setOf("m4s", "m4f", "mp4", "m4v", "m4a", "cmfv", "cmfa", "fmp4")

    /**
     * A master playlist, optionally completed with one of its media playlists ([bestMedia], usually the
     * best variant) which tells whether the stream is live, how long it is and how it is packaged.
     */
    fun fromHlsMaster(master: HlsPlaylist.Master, bestMedia: HlsPlaylist.Media?, mediaComplete: Boolean = true): InspectionResult {
        val media = bestMedia?.let { fromHlsMedia(it, mediaComplete) }
        return InspectionResult(
            isLive = media?.isLive,
            durationSec = media?.durationSec,
            variants = HlsParser.videoVariants(master),
            subtitles = HlsParser.subtitleTracks(master),
            drm = HlsParser.detectDrm(master) ?: media?.drm,
            isMasterPlaylist = true,
            hlsFragmentedMp4 = media?.hlsFragmentedMp4,
            childPlaylistUrls = childPlaylistUrls(master),
        )
    }

    /**
     * A media playlist. When [complete] is false the body was cut at the read limit, so a missing
     * EXT-X-ENDLIST says nothing about liveness and the summed duration is partial.
     */
    fun fromHlsMedia(media: HlsPlaylist.Media, complete: Boolean = true): InspectionResult {
        val isLive: Boolean? = when {
            complete -> media.isLive
            media.playlistType.equals("VOD", ignoreCase = true) -> false
            else -> null
        }
        return InspectionResult(
            isLive = isLive,
            durationSec = media.totalDurationSec.takeIf { complete && isLive == false && it > 0 },
            drm = HlsParser.detectDrm(media),
            isMasterPlaylist = false,
            hlsFragmentedMp4 = isFragmentedMp4(media),
        )
    }

    fun fromDash(info: DashManifestInfo): InspectionResult = InspectionResult(
        isLive = info.isLive,
        durationSec = info.durationSec?.takeIf { !info.isLive && it > 0 },
        variants = info.representations,
        subtitles = info.subtitles,
        drm = info.drm,
        isMasterPlaylist = false,
    )

    /** The media playlist worth fetching for details: the best video variant. */
    fun bestVariantUrl(master: HlsPlaylist.Master): String? =
        HlsParser.videoVariants(master).firstOrNull()?.url ?: master.variants.firstOrNull()?.url

    /** Every playlist a master points to (variants, renditions, I-frame playlists), de-duplicated. */
    fun childPlaylistUrls(master: HlsPlaylist.Master): List<String> =
        (master.variants.map { it.url } + master.renditions.mapNotNull { it.url } + master.iFrameVariantUrls).distinct()

    /** True with EXT-X-MAP or CMAF segment names, false for other segments (MPEG-TS, packed audio), null without segments. */
    fun isFragmentedMp4(media: HlsPlaylist.Media): Boolean? {
        if (media.hasInitSegment) return true
        val firstSegment = media.firstSegmentUrl
        if (media.segmentCount == 0 || firstSegment == null) return null
        return UrlUtils.extensionOf(firstSegment) in FRAGMENTED_MP4_EXTENSIONS
    }
}
