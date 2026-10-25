package app.snapcast.detection.inspect

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InspectionHeadersTest {
    private val video = DetectedVideo(
        id = "v",
        tabId = "t",
        url = "https://cdn.example/hls/master.m3u8",
        type = StreamType.HLS,
        headers = MediaHeaders(
            referer = "https://site.example/watch",
            userAgent = "UA",
            origin = "https://site.example",
            cookie = "own=1",
            extra = mapOf("Authorization" to "Bearer t", "Range" to "bytes=0-", "accept-encoding" to "br"),
        ),
        cookiesByHost = mapOf("edge.example" to "edge=2"),
    )

    @Test
    fun `the video's own headers are replayed on its host`() {
        assertThat(InspectionHeaders.forRequest(video)).containsExactly(
            "Authorization", "Bearer t",
            "Cookie", "own=1",
            "Origin", "https://site.example",
            "Referer", "https://site.example/watch",
            "User-Agent", "UA",
        )
    }

    @Test
    fun `other hosts get their own cookie and no credentials of the video's host`() {
        assertThat(InspectionHeaders.forRequest(video, "https://edge.example/720/index.m3u8")).containsExactly(
            "Cookie", "edge=2",
            "Origin", "https://site.example",
            "Referer", "https://site.example/watch",
            "User-Agent", "UA",
        )
        assertThat(InspectionHeaders.forRequest(video, "https://other.example/x.m3u8")).doesNotContainKey("Cookie")
    }

    @Test
    fun `a cookie seen for the video's host wins over the captured one`() {
        val updated = video.copy(cookiesByHost = mapOf("cdn.example" to "fresh=3"))
        assertThat(InspectionHeaders.forRequest(updated)["Cookie"]).isEqualTo("fresh=3")
    }

    @Test
    fun `no headers captured means none replayed`() {
        assertThat(InspectionHeaders.forRequest(video.copy(headers = MediaHeaders.NONE, cookiesByHost = emptyMap()))).isEmpty()
    }
}
