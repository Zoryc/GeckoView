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
    fun `non-ascii details and a long page address still fit the link budget`() {
        val cyrillic = report(details = "Видео не воспроизводится, ошибка доступа. ".repeat(60), includeUrl = true)
        val cjk = report(details = "视频无法播放，服务器拒绝访问。".repeat(200))
        // Every offset of the cut within a surrogate pair's encoding (12 characters per emoji).
        val emoji = (0..11).flatMap { pad -> listOf(false, true).map { report(details = "x".repeat(pad) + "😀".repeat(3_000), includeUrl = it) } }
        for (r in listOf(cyrillic, cjk) + emoji) {
            val url = r.issueUrl("Zoryc/Snapcast")
            assertThat(url.length).isAtMost(SiteReport.MAX_ISSUE_URL)
            // No half surrogate pair (URLEncoder writes a lone one as "?", %3F; these details have none), and the
            // report says it was shortened.
            assertThat(url.substringAfter("&details=").substringBefore('&')).doesNotContain("%3F")
            assertThat(URLDecoder.decode(url, "UTF-8")).contains("(shortened")
        }
        // The address the user agreed to share stays; the details are shortened instead.
        assertThat(URLDecoder.decode(cyrillic.issueUrl("Zoryc/Snapcast"), "UTF-8")).contains("site=https://www.site.example/watch/42?user=SECRET-USER&")
        val longAddress = report(includeUrl = true).copy(pageUrl = "https://www.site.example/watch?" + "q=1&".repeat(3_000))
        val url = longAddress.issueUrl("Zoryc/Snapcast")
        assertThat(url.length).isAtMost(SiteReport.MAX_ISSUE_URL)
        assertThat(URLDecoder.decode(url, "UTF-8")).contains("site=site.example&")
    }

    @Test
    fun `long details and long diagnostics both keep a part`() {
        val events = List(8) { ReportEvent(it.toLong(), "player", "Ошибка воспроизведения ".repeat(12)) }
        val r = report(details = "Сайт не работает. ".repeat(300)).copy(events = events)
        val url = r.issueUrl("Zoryc/Snapcast")
        assertThat(url.length).isAtMost(SiteReport.MAX_ISSUE_URL)
        val fields = url.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertThat(fields.getValue("details")).startsWith("Сайт не работает.")
        assertThat(fields.getValue("details").length).isGreaterThan(300)
        assertThat(fields.getValue("diagnostics")).startsWith("- App: Snapcast")
        assertThat(fields.getValue("diagnostics").length).isGreaterThan(300)
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
