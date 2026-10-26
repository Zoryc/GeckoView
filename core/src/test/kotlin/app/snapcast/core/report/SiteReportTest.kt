package app.snapcast.core.report

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DetectionSource
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.VideoVariant
import com.google.common.truth.Truth.assertThat
import java.net.URLDecoder
import org.junit.Test

class SiteReportTest {
    private val environment = ReportEnvironment("0.1.1", "158.0", "15 (SDK 35)", "Google Pixel 7")
    private val hls = DetectedVideo(
        id = "1",
        tabId = "t",
        url = "https://cdn.example.net/hls/master.m3u8?token=SECRET-TOKEN",
        type = StreamType.HLS,
        headers = MediaHeaders(referer = "https://embed.example.org/", cookie = "sess=SECRET-COOKIE", extra = mapOf("Authorization" to "Bearer SECRET")),
        cookiesByHost = mapOf("cdn.example.net" to "edge=SECRET-EDGE"),
        variants = listOf(
            VideoVariant("https://cdn.example.net/hls/360.m3u8?token=SECRET-TOKEN", width = 640, height = 360, bandwidth = 800_000),
            VideoVariant("https://cdn.example.net/hls/1080.m3u8?token=SECRET-TOKEN", width = 1920, height = 1080, bandwidth = 5_000_000),
        ),
        durationSec = 24.0,
        posterUrl = "https://www.site.example/og.jpg?sig=SECRET-SIG",
        posterReferer = "https://www.site.example/watch/42?user=SECRET-USER",
        source = DetectionSource.NETWORK,
    )

    private fun report(includeUrl: Boolean = false, videos: List<DetectedVideo> = listOf(hls), details: String = "Plays for a second, then 403") = SiteReport(
        pageUrl = "https://www.site.example/watch/42?user=SECRET-USER#t=10",
        kind = ProblemKind.WONT_PLAY,
        details = details,
        includePageUrl = includeUrl,
        environment = environment,
        videos = videos,
        events = listOf(ReportEvent(1L, "player", "HTTP 403 from cdn.example.net (ERROR_CODE_IO_BAD_HTTP_STATUS)")),
    )

    @Test
    fun `the report never contains links, cookies or tokens`() {
        val text = report().body + report().issueUrl("Zoryc/Snapcast")
        assertThat(text).doesNotContain("SECRET")
        assertThat(URLDecoder.decode(report().issueUrl("Zoryc/Snapcast"), "UTF-8")).doesNotContain("SECRET")
        assertThat(report().site).isEqualTo("site.example")
    }

    @Test
    fun `the full page address only with consent, without its fragment`() {
        assertThat(report(includeUrl = true).site).isEqualTo("https://www.site.example/watch/42?user=SECRET-USER")
    }

    @Test
    fun `videos are described by what the app saw`() {
        val diagnostics = report().diagnostics
        assertThat(diagnostics).contains("- App: Snapcast 0.1.1 (GeckoView 158.0), Android 15 (SDK 35), Google Pixel 7")
        assertThat(diagnostics).contains("1. HLS · up to 1080p (2 qualities) · 0:24 · from cdn.example.net (network) · poster from site.example · captured: referer, cookies, authorization")
        assertThat(diagnostics).contains("- player: HTTP 403 from cdn.example.net (ERROR_CODE_IO_BAD_HTTP_STATUS)")
        assertThat(report(videos = emptyList()).diagnostics).contains("- Videos found on the page: none")
    }

    @Test
    fun `the issue link fills the form and stays short`() {
        val url = report(details = "x".repeat(10_000)).issueUrl("Zoryc/Snapcast")
        assertThat(url).startsWith("https://github.com/Zoryc/Snapcast/issues/new?template=site-problem.yml&title=site.example%3A%20Video%20won%27t%20play&site=site.example&problem=")
        assertThat(url.length).isLessThan(8_000)
        val many = report(videos = List(40) { hls.copy(id = "$it") }).issueUrl("Zoryc/Snapcast")
        assertThat(many.length).isLessThan(8_000)
    }
}
