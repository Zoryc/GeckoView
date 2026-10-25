package app.snapcast.detection.inspect

import app.snapcast.core.media.DashManifestInfo
import app.snapcast.core.media.HlsKey
import app.snapcast.core.media.HlsPlaylist
import app.snapcast.core.media.HlsRendition
import app.snapcast.core.media.HlsVariant
import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaylistInspectionTest {
    private fun variant(url: String, height: Int?, bandwidth: Long) = HlsVariant(
        url = url,
        bandwidth = bandwidth,
        averageBandwidth = null,
        width = height?.let { it * 16 / 9 },
        height = height,
        codecs = null,
        frameRate = null,
        audioGroup = null,
        subtitlesGroup = null,
    )

    private fun rendition(type: String, url: String?) = HlsRendition(
        type = type,
        groupId = "g",
        name = type,
        language = "en",
        url = url,
        isDefault = false,
        autoSelect = false,
        forced = false,
    )

    private fun media(
        hasEndList: Boolean = true,
        playlistType: String? = null,
        keys: List<HlsKey> = emptyList(),
        hasInitSegment: Boolean = false,
        firstSegmentUrl: String? = "https://cdn.example/720/seg-1.ts",
        segmentCount: Int = 3,
    ) = HlsPlaylist.Media(
        targetDurationSec = 6.0,
        totalDurationSec = 18.0,
        segmentCount = segmentCount,
        hasEndList = hasEndList,
        playlistType = playlistType,
        keys = keys,
        hasInitSegment = hasInitSegment,
        firstSegmentUrl = firstSegmentUrl,
    )

    private val master = HlsPlaylist.Master(
        variants = listOf(
            variant("https://cdn.example/1080.m3u8", 1080, 6_000_000),
            variant("https://cdn.example/720.m3u8", 720, 3_000_000),
        ),
        renditions = listOf(
            rendition("AUDIO", "https://cdn.example/audio.m3u8"),
            rendition("SUBTITLES", "https://cdn.example/subs.m3u8"),
            rendition("CLOSED-CAPTIONS", null),
        ),
        sessionKeys = emptyList(),
        iFrameVariantUrls = listOf("https://cdn.example/iframes.m3u8", "https://cdn.example/720.m3u8"),
    )

    @Test
    fun `media playlists tell liveness, duration and packaging`() {
        val vod = PlaylistInspection.fromHlsMedia(media())
        assertThat(vod.isLive).isFalse()
        assertThat(vod.durationSec).isEqualTo(18.0)
        assertThat(vod.isMasterPlaylist).isFalse()
        assertThat(vod.hlsFragmentedMp4).isFalse()
        assertThat(vod.drm).isNull()

        val live = PlaylistInspection.fromHlsMedia(media(hasEndList = false, hasInitSegment = true))
        assertThat(live.isLive).isTrue()
        assertThat(live.durationSec).isNull()
        assertThat(live.hlsFragmentedMp4).isTrue()
    }

    @Test
    fun `a truncated media playlist does not claim to be live`() {
        val cut = PlaylistInspection.fromHlsMedia(media(hasEndList = false), complete = false)
        assertThat(cut.isLive).isNull()
        assertThat(cut.durationSec).isNull()

        val cutVod = PlaylistInspection.fromHlsMedia(media(hasEndList = false, playlistType = "VOD"), complete = false)
        assertThat(cutVod.isLive).isFalse()
        assertThat(cutVod.durationSec).isNull()
    }

    @Test
    fun `fragmented MP4 is recognised from EXT-X-MAP or CMAF segment names`() {
        assertThat(PlaylistInspection.isFragmentedMp4(media(hasInitSegment = true))).isTrue()
        assertThat(PlaylistInspection.isFragmentedMp4(media(firstSegmentUrl = "https://cdn.example/v/1.m4s"))).isTrue()
        assertThat(PlaylistInspection.isFragmentedMp4(media(firstSegmentUrl = "https://cdn.example/v/1.ts?x=1"))).isFalse()
        assertThat(PlaylistInspection.isFragmentedMp4(media(firstSegmentUrl = "https://cdn.example/v/1.aac"))).isFalse()
        assertThat(PlaylistInspection.isFragmentedMp4(media(firstSegmentUrl = null, segmentCount = 0))).isNull()
    }

    @Test
    fun `DRM keys of a media playlist are reported`() {
        val key = HlsKey(method = "SAMPLE-AES", uri = "skd://key", keyFormat = "com.apple.streamingkeydelivery", keyFormatVersions = "1")
        assertThat(PlaylistInspection.fromHlsMedia(media(keys = listOf(key))).drm).isNotNull()

        val aes = HlsKey(method = "AES-128", uri = "https://cdn.example/key", keyFormat = null, keyFormatVersions = null)
        assertThat(PlaylistInspection.fromHlsMedia(media(keys = listOf(aes))).drm).isNull()
    }

    @Test
    fun `a master lists its child playlists and the best variant`() {
        assertThat(PlaylistInspection.childPlaylistUrls(master)).containsExactly(
            "https://cdn.example/1080.m3u8",
            "https://cdn.example/720.m3u8",
            "https://cdn.example/audio.m3u8",
            "https://cdn.example/subs.m3u8",
            "https://cdn.example/iframes.m3u8",
        ).inOrder()
        assertThat(PlaylistInspection.bestVariantUrl(master)).isEqualTo("https://cdn.example/1080.m3u8")
        assertThat(PlaylistInspection.bestVariantUrl(master.copy(variants = emptyList()))).isNull()
    }

    @Test
    fun `a master combines its variants with the facts of its best media playlist`() {
        val result = PlaylistInspection.fromHlsMaster(master, media(hasInitSegment = true))

        assertThat(result.isMasterPlaylist).isTrue()
        assertThat(result.variants.map { it.height }).containsExactly(1080, 720).inOrder()
        assertThat(result.subtitles.map { it.url }).containsExactly("https://cdn.example/subs.m3u8")
        assertThat(result.isLive).isFalse()
        assertThat(result.durationSec).isEqualTo(18.0)
        assertThat(result.hlsFragmentedMp4).isTrue()
        assertThat(result.childPlaylistUrls).hasSize(5)

        val alone = PlaylistInspection.fromHlsMaster(master, bestMedia = null)
        assertThat(alone.isLive).isNull()
        assertThat(alone.durationSec).isNull()
        assertThat(alone.hlsFragmentedMp4).isNull()
    }

    @Test
    fun `session keys of a master mark it as DRM protected`() {
        val key = HlsKey(method = "SAMPLE-AES", uri = "data:text/plain;base64,AAAA", keyFormat = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", keyFormatVersions = "1")
        val result = PlaylistInspection.fromHlsMaster(master.copy(sessionKeys = listOf(key)), media())
        assertThat(result.drm).isNotNull()
    }

    @Test
    fun `DASH manifests map to representations, liveness and DRM`() {
        val representation = VideoVariant(url = "https://cdn.example/a.mpd", height = 1080, bandwidth = 5_000_000)
        val subtitle = SubtitleTrack(id = "en", url = "https://cdn.example/en.vtt")
        val drm = DrmInfo("Widevine", "DASH ContentProtection")
        val vod = PlaylistInspection.fromDash(DashManifestInfo(false, 634.5, listOf(representation), drm, listOf(subtitle)))

        assertThat(vod.isLive).isFalse()
        assertThat(vod.durationSec).isEqualTo(634.5)
        assertThat(vod.variants).containsExactly(representation)
        assertThat(vod.subtitles).containsExactly(subtitle)
        assertThat(vod.drm).isEqualTo(drm)
        assertThat(vod.isMasterPlaylist).isFalse()

        val live = PlaylistInspection.fromDash(DashManifestInfo(true, 30.0, emptyList(), null, emptyList()))
        assertThat(live.isLive).isTrue()
        assertThat(live.durationSec).isNull()
    }
}
