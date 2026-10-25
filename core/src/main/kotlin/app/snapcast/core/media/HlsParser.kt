package app.snapcast.core.media

import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant

data class HlsKey(
    val method: String,
    val uri: String?,
    val keyFormat: String?,
    val keyFormatVersions: String?,
) {
    /**
     * True for DRM key systems (FairPlay/Widevine/PlayReady). Plain AES-128 or SAMPLE-AES with the
     * "identity" key format are not DRM: the key URI is fetchable and can be proxied.
     */
    val isDrm: Boolean get() = TODO()
}

data class HlsVariant(
    val url: String,
    val bandwidth: Long?,
    val averageBandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
    val frameRate: Double?,
    val audioGroup: String?,
    val subtitlesGroup: String?,
) {
    fun toVideoVariant(): VideoVariant = VideoVariant(
        url = url,
        bandwidth = bandwidth,
        width = width,
        height = height,
        codecs = codecs,
        frameRate = frameRate,
    )
}

data class HlsRendition(
    /** AUDIO, VIDEO, SUBTITLES or CLOSED-CAPTIONS. */
    val type: String,
    val groupId: String?,
    val name: String?,
    val language: String?,
    /** Absolute URL, null for renditions muxed into the variant. */
    val url: String?,
    val isDefault: Boolean,
    val autoSelect: Boolean,
    val forced: Boolean,
)

sealed interface HlsPlaylist {
    data class Master(
        /** Sorted by descending height, then bandwidth. Absolute URLs. */
        val variants: List<HlsVariant>,
        val renditions: List<HlsRendition>,
        val sessionKeys: List<HlsKey>,
        /** Absolute URLs from EXT-X-I-FRAME-STREAM-INF. */
        val iFrameVariantUrls: List<String>,
    ) : HlsPlaylist {
        val subtitleRenditions: List<HlsRendition> get() = renditions.filter { it.type == "SUBTITLES" && it.url != null }
    }

    data class Media(
        val targetDurationSec: Double?,
        val totalDurationSec: Double,
        val segmentCount: Int,
        val hasEndList: Boolean,
        /** EXT-X-PLAYLIST-TYPE value (VOD / EVENT) or null. */
        val playlistType: String?,
        val keys: List<HlsKey>,
        /** True when EXT-X-MAP is present (fragmented MP4 / CMAF segments). */
        val hasInitSegment: Boolean,
        /** Absolute URL of the first media segment, if any. */
        val firstSegmentUrl: String?,
    ) : HlsPlaylist {
        val isLive: Boolean get() = !hasEndList && playlistType != "VOD"
    }
}

/** Minimal, tolerant HLS (RFC 8216) playlist parser. */
object HlsParser {
    /** Returns null when [text] is not an HLS playlist. Relative URIs are resolved against [playlistUrl]. */
    fun parse(text: String, playlistUrl: String): HlsPlaylist? = TODO()

    /** Parses an attribute list like `BANDWIDTH=1280000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720`. */
    fun parseAttributes(attributeList: String): Map<String, String> = TODO()

    /** DRM signal for a parsed playlist (session keys of a master, keys of a media playlist), or null. */
    fun detectDrm(playlist: HlsPlaylist): DrmInfo? = TODO()

    /** Subtitle tracks advertised by a master playlist (SUBTITLES renditions), as [SubtitleTrack]s with format VTT. */
    fun subtitleTracks(master: HlsPlaylist.Master): List<SubtitleTrack> = TODO()

    /** Variants mapped to [VideoVariant], best first, de-duplicated by resolution+bandwidth. */
    fun videoVariants(master: HlsPlaylist.Master): List<VideoVariant> = TODO()
}
