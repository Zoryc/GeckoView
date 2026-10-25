package app.snapcast.core.detection

import app.snapcast.core.model.AppSettings
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class DetectionFilterTest {
    private val settings = AppSettings()

    private fun video(
        url: String = "https://cdn.example.com/v.mp4",
        type: StreamType = StreamType.MP4,
        pageUrl: String? = "https://site.example.com/watch",
        size: Long? = null,
        duration: Double? = null,
        isLive: Boolean = false,
    ) = DetectedVideo(
        id = "id",
        tabId = "tab",
        url = url,
        type = type,
        pageUrl = pageUrl,
        sizeBytes = size,
        durationSec = duration,
        isLive = isLive,
    )

    @Test
    fun `plain video is visible`() {
        assertThat(DetectionFilter.isVisible(video(), settings)).isTrue()
    }

    @Test
    fun `ignored media host or page host hides the video including subdomains`() {
        assertThat(DetectionFilter.isVisible(video(url = "https://ads.doubleclick.net/vast/ad.mp4"), settings)).isFalse()
        assertThat(DetectionFilter.isVisible(video(url = "https://imasdk.googleapis.com/x.m3u8", type = StreamType.HLS), settings))
            .isFalse()
        assertThat(DetectionFilter.isVisible(video(url = "https://googleapis.com/x.m3u8", type = StreamType.HLS), settings)).isTrue()
        val custom = settings.copy(ignoredDomains = setOf("Example.COM"))
        assertThat(DetectionFilter.isVisible(video(url = "https://other.net/v.mp4", pageUrl = "https://www.example.com/p"), custom))
            .isFalse()
        assertThat(DetectionFilter.isVisible(video(url = "https://cdn.example.com/v.mp4", pageUrl = null), custom)).isFalse()
        assertThat(DetectionFilter.isVisible(video(url = "https://notexample.com/v.mp4", pageUrl = null), custom)).isTrue()
    }

    @Test
    fun `small progressive files are hidden only when the size is known`() {
        val min = settings.minVideoSizeBytes
        listOf(StreamType.MP4, StreamType.WEBM, StreamType.MKV, StreamType.TS, StreamType.OTHER).forEach { type ->
            assertWithMessage(type.name).that(DetectionFilter.isVisible(video(type = type, size = min - 1), settings)).isFalse()
            assertWithMessage(type.name).that(DetectionFilter.isVisible(video(type = type, size = min), settings)).isTrue()
            assertWithMessage(type.name).that(DetectionFilter.isVisible(video(type = type, size = null), settings)).isTrue()
        }
    }

    @Test
    fun `manifests ignore the size rule`() {
        assertThat(DetectionFilter.isVisible(video(type = StreamType.HLS, size = 900), settings)).isTrue()
        assertThat(DetectionFilter.isVisible(video(type = StreamType.DASH, size = 900), settings)).isTrue()
    }

    @Test
    fun `short known durations are hidden`() {
        assertThat(DetectionFilter.isVisible(video(duration = 4.9), settings)).isFalse()
        assertThat(DetectionFilter.isVisible(video(duration = 5.0), settings)).isTrue()
        assertThat(DetectionFilter.isVisible(video(type = StreamType.HLS, duration = 3.0), settings)).isFalse()
        assertThat(DetectionFilter.isVisible(video(type = StreamType.HLS, duration = null), settings)).isTrue()
        assertThat(DetectionFilter.isVisible(video(type = StreamType.HLS, duration = 2.0, isLive = true), settings)).isTrue()
        assertThat(DetectionFilter.isVisible(video(duration = 0.0), settings)).isTrue()
    }

    @Test
    fun `zero minimum duration disables the duration rule`() {
        val noMin = settings.copy(minVideoDurationSec = 0)
        assertThat(DetectionFilter.isVisible(video(duration = 1.0), noMin)).isTrue()
    }

    @Test
    fun `apply keeps order and filters`() {
        val keep1 = video(url = "https://a.com/1.mp4")
        val drop = video(url = "https://a.com/2.mp4", size = 10)
        val keep2 = video(url = "https://b.com/3.m3u8", type = StreamType.HLS)
        assertThat(DetectionFilter.apply(listOf(keep1, drop, keep2), settings)).containsExactly(keep1, keep2).inOrder()
    }
}
