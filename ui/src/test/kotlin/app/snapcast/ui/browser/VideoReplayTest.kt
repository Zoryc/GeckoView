package app.snapcast.ui.browser

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VideoReplayTest {
    private fun video(url: String, detectedAt: Long) =
        DetectedVideo(id = url, tabId = "t", url = url, type = StreamType.HLS, detectedAt = detectedAt)

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
    fun `the same link wins, then the renewed link that keeps the most of the original query`() {
        val original = "https://cdn.example/hls/master.m3u8?v=111&sig=old"
        val main = video("https://cdn.example/hls/master.m3u8?v=111&sig=new", detectedAt = 2_000)
        val preview = video("https://cdn.example/hls/master.m3u8?v=222&sig=new2", detectedAt = 2_000)
        assertThat(VideoReplay.find(listOf(preview, main), original, since = 1_000)).isEqualTo(main)
        val same = video(original, detectedAt = 2_000)
        assertThat(VideoReplay.find(listOf(main, same), original, since = 1_000)).isEqualTo(same)
        // Two equally good candidates: the user picks.
        val a = video("https://cdn.example/hls/master.m3u8?t=1", detectedAt = 2_000)
        val b = video("https://cdn.example/hls/master.m3u8?t=2", detectedAt = 2_000)
        assertThat(VideoReplay.find(listOf(a, b), "https://cdn.example/hls/master.m3u8?t=0", since = 1_000)).isNull()
    }

    @Test
    fun `a preview on the same path that shows up first is not played`() = runTest {
        val original = "https://cdn.example/hls/master.m3u8?v=111&sig=old"
        val main = video("https://cdn.example/hls/master.m3u8?v=111&sig=new", detectedAt = 2_000)
        val preview = video("https://cdn.example/hls/master.m3u8?v=222&sig=new2", detectedAt = 2_000)
        val list = MutableStateFlow(emptyList<DetectedVideo>())
        val found = async { VideoReplay.await(list, original, since = 1_000, settleMs = 2_000) }
        list.value = listOf(preview)
        advanceTimeBy(500)
        list.value = listOf(preview, main)
        // A change that is not a new candidate (a title, cookies...) does not delay the choice.
        advanceTimeBy(1_500)
        list.value = listOf(preview.copy(title = "Trailer"), main)
        advanceTimeBy(501)
        runCurrent()
        assertThat(found.await()).isEqualTo(main)
    }

    @Test
    fun `candidates that stay ambiguous give no video`() = runTest {
        val list = MutableStateFlow(
            listOf(
                video("https://cdn.example/hls/master.m3u8?t=1", detectedAt = 2_000),
                video("https://cdn.example/hls/master.m3u8?t=2", detectedAt = 2_000),
            ),
        )
        assertThat(VideoReplay.await(list, "https://cdn.example/hls/master.m3u8?t=0", since = 1_000, settleMs = 2_000)).isNull()
    }
}
