package app.snapcast.detection

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import app.snapcast.core.util.UrlUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentUrlsTest {
    @Test
    fun `a hello from the document the list belongs to is not a new document`() {
        assertThat(DocumentUrls.isNewDocument("https://a.example/watch#t=5", "https://a.example/watch", null)).isFalse()
        assertThat(DocumentUrls.isNewDocument("https://a.example/watch/", "https://a.example/watch", null)).isFalse()
    }

    @Test
    fun `redirect targets and pushState URLs reported by the session count as the same document`() {
        assertThat(DocumentUrls.isNewDocument("https://www.a.example/", "http://a.example/", "https://www.a.example/")).isFalse()
    }

    @Test
    fun `a hello from another URL means the page start was missed`() {
        assertThat(DocumentUrls.isNewDocument("https://b.example/", "https://a.example/", "https://a.example/next")).isTrue()
        assertThat(DocumentUrls.isNewDocument("https://b.example/", null, null)).isTrue()
        assertThat(DocumentUrls.isNewDocument("  ", "https://a.example/", null)).isFalse()
    }

    @Test
    fun `direct media documents are recognised`() {
        assertThat(DocumentUrls.isDirectMedia("https://cdn.example/movie.mp4")).isTrue()
        assertThat(DocumentUrls.isDirectMedia("https://cdn.example/live/master.m3u8?token=1")).isTrue()
        assertThat(DocumentUrls.isDirectMedia("https://cdn.example/watch?v=1")).isFalse()
        assertThat(DocumentUrls.isDirectMedia("https://cdn.example/seg-1.ts")).isFalse()
        assertThat(DocumentUrls.isDirectMedia("file:///sdcard/movie.mp4")).isFalse()
        assertThat(DocumentUrls.isDirectMedia("snapcast:home")).isFalse()
    }

    @Test
    fun `the direct media report describes the document itself`() {
        val report = DocumentUrls.directMediaReport("https://cdn.example/movie.mp4", 123L)
        assertThat(report.url).isEqualTo("https://cdn.example/movie.mp4")
        assertThat(report.source).isEqualTo("network")
        assertThat(report.resourceType).isEqualTo("main_frame")
        assertThat(report.pageUrl).isEqualTo("https://cdn.example/movie.mp4")
        assertThat(report.frameUrl).isEqualTo("https://cdn.example/movie.mp4")
        assertThat(report.timestamp).isEqualTo(123L)
        assertThat(report.mime).isNull()
        assertThat(DocumentUrls.directMediaReport("https://cdn.example/movie.webm", 1L, "video/webm").mime).isEqualTo("video/webm")
    }

    @Test
    fun `a document already in the list is not probed again`() {
        val listed = video("https://cdn.example/live/master.m3u8")
        assertThat(DocumentUrls.isListed("https://cdn.example/live/master.m3u8", listOf(listed))).isTrue()
        // LL-HLS directives are stripped from listed URLs.
        assertThat(DocumentUrls.isListed("https://cdn.example/live/master.m3u8?_HLS_msn=4", listOf(listed))).isTrue()
        assertThat(DocumentUrls.isListed("https://cdn.example/other.mp4", listOf(listed))).isFalse()
        assertThat(DocumentUrls.isListed("https://cdn.example/other.mp4", emptyList())).isFalse()
    }

    @Test
    fun `the probe goes out with the tab's user agent and per-host cookies when known`() {
        val bare = DocumentUrls.probeTarget("https://cdn.example/movie.webm", "tab", isPrivate = true, known = emptyList())
        assertThat(bare.url).isEqualTo("https://cdn.example/movie.webm")
        assertThat(bare.type).isEqualTo(StreamType.WEBM)
        assertThat(bare.isPrivate).isTrue()
        assertThat(bare.headers).isEqualTo(MediaHeaders.NONE)
        assertThat(bare.cookiesByHost).isEmpty()

        val known = listOf(
            video("https://a.example/x.mp4", MediaHeaders(userAgent = "Old UA", cookie = "a=1"), mapOf("cdn.example" to "c=old")),
            video("https://b.example/y.mp4", MediaHeaders(userAgent = "Gecko UA"), mapOf("cdn.example" to "c=new")),
        )
        val target = DocumentUrls.probeTarget("https://cdn.example/movie.webm", "tab", isPrivate = false, known = known)
        assertThat(target.headers).isEqualTo(MediaHeaders(userAgent = "Gecko UA"))
        assertThat(target.cookiesByHost).containsExactly("a.example", "a=1", "cdn.example", "c=new")
    }

    private fun video(url: String, headers: MediaHeaders = MediaHeaders.NONE, cookies: Map<String, String> = emptyMap()) =
        DetectedVideo(
            id = UrlUtils.stableId(url),
            tabId = "tab",
            url = url,
            type = StreamType.MP4,
            headers = headers,
            cookiesByHost = cookies,
        )
}
