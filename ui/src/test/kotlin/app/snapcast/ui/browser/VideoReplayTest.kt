package app.snapcast.ui.browser

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.VideoVariant
import app.snapcast.player.PlaybackQuality
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoReplayTest {
    private fun video(url: String, detectedAt: Long, variants: List<VideoVariant> = emptyList()) =
        DetectedVideo(id = url, tabId = "t", url = url, type = StreamType.HLS, detectedAt = detectedAt, variants = variants)

    @Test
    fun `a renewed link differs only in its query`() {
        assertThat(VideoReplay.keyOf("https://CDN.example/hls/master.m3u8?t=1-abc"))
            .isEqualTo(VideoReplay.keyOf("https://cdn.example/hls/master.m3u8?t=2-def#x"))
        assertThat(VideoReplay.keyOf("https://cdn.example/hls/master.m3u8"))
            .isNotEqualTo(VideoReplay.keyOf("https://cdn.example/hls/other.m3u8"))
        assertThat(VideoReplay.keyOf("blob:https://site.example/1")).isNull()
    }

    @Test
    fun `only videos found after the reload count`() {
        val stale = video("https://cdn.example/hls/master.m3u8?t=old", detectedAt = 100)
        val fresh = video("https://cdn.example/hls/master.m3u8?t=new", detectedAt = 2_000)
        val other = video("https://cdn.example/ads/master.m3u8?t=new", detectedAt = 2_000)
        assertThat(VideoReplay.find(listOf(stale, other, fresh), "https://cdn.example/hls/master.m3u8?t=old", since = 1_000)).isEqualTo(fresh)
        assertThat(VideoReplay.find(listOf(stale, other), "https://cdn.example/hls/master.m3u8?t=old", since = 1_000)).isNull()
    }

    @Test
    fun `the quality picked before the reload is found again`() {
        val variants = listOf(
            VideoVariant(url = "https://cdn.example/1080.m3u8?t=new", width = 1920, height = 1080, bandwidth = 5_000_000),
            VideoVariant(url = "https://cdn.example/360.m3u8?t=new", width = 640, height = 360, bandwidth = 800_000),
        )
        val fresh = video("https://cdn.example/master.m3u8?t=new", 1, variants)
        assertThat(VideoReplay.variantFor(fresh, PlaybackQuality(640, 360, 800_000))).isEqualTo(variants[1])
        assertThat(VideoReplay.variantFor(fresh, PlaybackQuality(bandwidth = 5_000_000))).isEqualTo(variants[0])
        assertThat(VideoReplay.variantFor(fresh, null)).isNull()
    }
}
