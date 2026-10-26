package app.snapcast.ui.video

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.util.Formatters
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoLabelsTest {
    private fun video(
        url: String = "https://www.cdn.example.com/v/movie.mp4",
        type: StreamType = StreamType.MP4,
    ) = DetectedVideo(id = "id", tabId = "tab", url = url, type = type)

    @Test
    fun `minimal video shows type and domain`() {
        val chips = VideoLabels.chips(video())
        assertThat(chips).containsExactly(
            VideoChip(VideoChipKind.TYPE, "MP4"),
            VideoChip(VideoChipKind.DOMAIN, "cdn.example.com"),
        ).inOrder()
    }

    @Test
    fun `all known metadata in display order`() {
        val v = video(type = StreamType.MP4).copy(
            variants = listOf(
                VideoVariant(url = "https://cdn.example.com/720.m3u8", height = 720, bandwidth = 2_000_000),
                VideoVariant(url = "https://cdn.example.com/1080.m3u8", height = 1080, bandwidth = 5_000_000),
            ),
            durationSec = 125.0,
            sizeBytes = 3_000_000L,
            subtitles = listOf(SubtitleTrack(id = "s", url = "https://cdn.example.com/en.vtt")),
            drm = DrmInfo(reason = "EME request"),
        )
        val chips = VideoLabels.chips(v.copy(width = 1920, height = 1080))
        assertThat(chips.map { it.kind }).containsExactly(
            VideoChipKind.TYPE,
            VideoChipKind.QUALITY,
            VideoChipKind.DURATION,
            VideoChipKind.SIZE,
            VideoChipKind.SUBTITLES,
            VideoChipKind.DOMAIN,
            VideoChipKind.DRM,
        ).inOrder()
        assertThat(chips.first { it.kind == VideoChipKind.TYPE }.text).isEqualTo("MP4")
        assertThat(chips.first { it.kind == VideoChipKind.QUALITY }.text).isEqualTo("1080p")
        assertThat(chips.first { it.kind == VideoChipKind.DURATION }.text).isEqualTo(Formatters.duration(125.0))
        assertThat(chips.first { it.kind == VideoChipKind.SIZE }.text).isEqualTo(Formatters.fileSize(3_000_000L))
    }

    @Test
    fun `an adaptive stream with several qualities shows its best one as a range, never its playlist size`() {
        val v = video(type = StreamType.HLS).copy(
            variants = listOf(
                VideoVariant(url = "https://cdn.example.com/720.m3u8", height = 720, bandwidth = 2_000_000),
                VideoVariant(url = "https://cdn.example.com/1080.m3u8", height = 1080, bandwidth = 5_000_000),
            ),
            height = 360,
            sizeBytes = 424L,
        )
        val chips = VideoLabels.chips(v)
        assertThat(chips.map { it.kind }).containsExactly(VideoChipKind.TYPE, VideoChipKind.QUALITY_RANGE, VideoChipKind.DOMAIN).inOrder()
        assertThat(chips[1].text).isEqualTo("1080p")
        // A picked quality replaces the range.
        val picked = VideoLabels.chips(v, picked = v.variants.first())
        assertThat(picked[1]).isEqualTo(VideoChip(VideoChipKind.QUALITY, "720p"))
    }

    @Test
    fun `an adaptive stream whose variants are not known yet shows no quality`() {
        val chips = VideoLabels.chips(video(type = StreamType.HLS).copy(height = 360))
        assertThat(chips.map { it.kind }).doesNotContain(VideoChipKind.QUALITY)
        assertThat(chips.map { it.kind }).doesNotContain(VideoChipKind.QUALITY_RANGE)
    }

    @Test
    fun `live streams show LIVE instead of a duration`() {
        val chips = VideoLabels.chips(video(type = StreamType.HLS).copy(isLive = true, durationSec = 30.0))
        val kinds = chips.map { it.kind }
        assertThat(kinds).contains(VideoChipKind.LIVE)
        assertThat(kinds).doesNotContain(VideoChipKind.DURATION)
        assertThat(chips.first { it.kind == VideoChipKind.LIVE }.text).isNull()
    }

    @Test
    fun `invalid durations and sizes are omitted`() {
        val chips = VideoLabels.chips(video().copy(durationSec = Double.NaN, sizeBytes = 0L))
        assertThat(chips.map { it.kind }).containsNoneOf(VideoChipKind.DURATION, VideoChipKind.SIZE)
    }

    @Test
    fun `element height is used as quality for progressive files`() {
        val chips = VideoLabels.chips(video().copy(height = 480))
        assertThat(chips.first { it.kind == VideoChipKind.QUALITY }.text).isEqualTo("480p")
    }

    @Test
    fun `quality choices are best first and de-duplicated`() {
        val v = video(type = StreamType.HLS).copy(
            variants = listOf(
                VideoVariant(url = "a", height = 360, bandwidth = 800_000),
                VideoVariant(url = "b", height = 1080, bandwidth = 5_000_000),
                VideoVariant(url = "c", height = 1080, bandwidth = 5_000_000),
                VideoVariant(url = "d", height = 720, bandwidth = 2_500_000),
            ),
        )
        assertThat(VideoLabels.qualityChoices(v).map { it.url }).containsExactly("b", "d", "a").inOrder()
    }

    @Test
    fun `DASH offers no quality choices`() {
        val manifest = "https://cdn.example.com/bbb_30fps.mpd"
        val v = video(url = manifest, type = StreamType.DASH).copy(
            variants = listOf(
                VideoVariant(url = manifest, height = 1080, bandwidth = 5_000_000),
                VideoVariant(url = manifest, height = 360, bandwidth = 800_000),
            ),
        )
        assertThat(VideoLabels.qualityChoices(v)).isEmpty()
    }

    @Test
    fun `variants without their own audio are not offered`() {
        val v = video(type = StreamType.HLS).copy(
            variants = listOf(
                VideoVariant(url = "a", height = 1080, bandwidth = 5_000_000, separateAudio = true),
                VideoVariant(url = "b", height = 720, bandwidth = 2_500_000, separateAudio = true),
            ),
        )
        assertThat(VideoLabels.qualityChoices(v)).isEmpty()
    }

    @Test
    fun `variant details combine bitrate resolution and codecs`() {
        val details = VideoLabels.variantDetails(
            VideoVariant(url = "x", bandwidth = 2_500_000, width = 1280, height = 720, codecs = "avc1.64001f"),
        )
        assertThat(details).isEqualTo("${VideoVariant.formatBitrate(2_500_000)} · 1280×720 · avc1.64001f")
        assertThat(VideoLabels.variantDetails(VideoVariant(url = "y"))).isNull()
    }
}
