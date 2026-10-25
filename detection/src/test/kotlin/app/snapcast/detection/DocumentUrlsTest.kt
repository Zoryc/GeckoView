package app.snapcast.detection

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
    }
}
