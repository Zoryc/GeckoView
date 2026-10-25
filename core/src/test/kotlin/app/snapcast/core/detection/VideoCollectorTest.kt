package app.snapcast.core.detection

import app.snapcast.core.model.DetectionSource
import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.util.UrlUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoCollectorTest {
    private var now = 1_000L
    private val collector = VideoCollector(tabId = "tab-1", isPrivate = false, clock = { now })

    private val page = "https://site.example.com/watch/42"

    private fun network(
        url: String,
        mime: String? = null,
        resourceType: String? = "media",
        size: Long? = null,
        headers: Map<String, String> = emptyMap(),
        status: Int? = 200,
        title: String? = null,
        cookiesByHost: Map<String, String> = emptyMap(),
    ) = MediaReport(
        url = url,
        source = "network",
        mime = mime,
        size = size,
        status = status,
        resourceType = resourceType,
        pageUrl = page,
        pageTitle = "Watch 42 - Site",
        requestHeaders = headers,
        cookiesByHost = cookiesByHost,
        title = title,
    )

    private fun dom(
        url: String,
        title: String? = null,
        poster: String? = null,
        duration: Double? = null,
        width: Int? = null,
        height: Int? = null,
        frameUrl: String? = null,
        isBlob: Boolean = false,
        tracks: List<TrackReport> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        mime: String? = null,
    ) = MediaReport(
        url = url,
        source = "dom",
        mime = mime,
        frameUrl = frameUrl,
        pageUrl = page,
        pageTitle = "Watch 42 - Site",
        requestHeaders = headers,
        duration = duration,
        width = width,
        height = height,
        poster = poster,
        title = title,
        isBlob = isBlob,
        tracks = tracks,
    )

    // ---- basic listing ----

    @Test
    fun `network mp4 is listed with metadata`() {
        val video = collector.add(
            network(
                "https://cdn.example.com/v/movie.mp4",
                mime = "video/mp4",
                size = 80_000_000,
                headers = mapOf(
                    "referer" to "https://site.example.com/",
                    "USER-AGENT" to "Gecko UA",
                    "Origin" to "https://site.example.com",
                    "Cookie" to "sid=1",
                    "Authorization" to "Bearer t",
                    "Accept" to "*/*",
                ),
                cookiesByHost = mapOf("img.example.com" to "x=1"),
            ),
        )!!
        assertThat(video.id).isEqualTo(UrlUtils.stableId("https://cdn.example.com/v/movie.mp4"))
        assertThat(video.tabId).isEqualTo("tab-1")
        assertThat(video.type).isEqualTo(StreamType.MP4)
        assertThat(video.mimeType).isEqualTo("video/mp4")
        assertThat(video.sizeBytes).isEqualTo(80_000_000L)
        assertThat(video.pageUrl).isEqualTo(page)
        assertThat(video.pageTitle).isEqualTo("Watch 42 - Site")
        assertThat(video.title).isEqualTo("Watch 42 - Site")
        assertThat(video.source).isEqualTo(DetectionSource.NETWORK)
        assertThat(video.detectedAt).isEqualTo(1_000L)
        assertThat(video.isPrivate).isFalse()
        assertThat(video.headers).isEqualTo(
            MediaHeaders(
                referer = "https://site.example.com/",
                userAgent = "Gecko UA",
                origin = "https://site.example.com",
                cookie = "sid=1",
                extra = mapOf("Authorization" to "Bearer t"),
            ),
        )
        assertThat(video.cookiesByHost).containsExactly("img.example.com", "x=1")
        assertThat(collector.videos).containsExactly(video)
        assertThat(collector.pageUrl).isEqualTo(page)
    }

    @Test
    fun `private collectors mark videos private`() {
        val privateCollector = VideoCollector("t", isPrivate = true, clock = { 5L })
        assertThat(privateCollector.add(network("https://e.com/a.m3u8"))!!.isPrivate).isTrue()
    }

    @Test
    fun `segments non media and failed responses are ignored`() {
        assertThat(collector.add(network("https://cdn.example.com/hls/seg-1.ts", mime = "video/mp2t"))).isNull()
        assertThat(collector.add(network("https://cdn.example.com/dash/chunk-1.m4s"))).isNull()
        assertThat(collector.add(network("https://cdn.example.com/v.mp4", mime = "video/mp4", resourceType = "xmlhttprequest", size = 400_000)))
            .isNull()
        assertThat(collector.add(network("https://cdn.example.com/logo.png", mime = "image/png"))).isNull()
        assertThat(collector.add(network("https://cdn.example.com/page", mime = "text/html", resourceType = "main_frame"))).isNull()
        assertThat(collector.add(network("https://cdn.example.com/missing.mp4", status = 404))).isNull()
        assertThat(collector.add(network("https://cdn.example.com/redirect.m3u8", status = 302))).isNull()
        assertThat(collector.add(network("ftp://cdn.example.com/a.mp4"))).isNull()
        assertThat(collector.videos).isEmpty()
        assertThat(collector.add(network("https://cdn.example.com/cached.mp4", status = 304))).isNotNull()
    }

    @Test
    fun `misconfigured content type falls back to the URL`() {
        val video = collector.add(network("https://cdn.example.com/live/master.m3u8", mime = "application/octet-stream"))!!
        assertThat(video.type).isEqualTo(StreamType.HLS)
        assertThat(video.mimeType).isNull()
    }

    @Test
    fun `dom http source without extension is listed as OTHER`() {
        val video = collector.add(dom("https://media.example.com/stream/abc123", title = "Clip"))!!
        assertThat(video.type).isEqualTo(StreamType.OTHER)
        assertThat(video.source).isEqualTo(DetectionSource.DOM)
        assertThat(video.title).isEqualTo("Clip")
    }

    @Test
    fun `dom source with an extension keeps its type`() {
        assertThat(collector.add(dom("https://media.example.com/a.webm"))!!.type).isEqualTo(StreamType.WEBM)
    }

    @Test
    fun `dom segment-looking source is ignored`() {
        assertThat(collector.add(dom("https://rr1.googlevideo.com/videoplayback?itag=18&expire=1"))).isNull()
        assertThat(collector.add(dom("https://media.example.com/seg-3.ts"))).isNull()
        assertThat(collector.videos).isEmpty()
    }

    @Test
    fun `dom report uses frame or page URL as referer`() {
        val inFrame = collector.add(dom("https://media.example.com/a.mp4", frameUrl = "https://player.example.net/embed/1"))!!
        assertThat(inFrame.headers.referer).isEqualTo("https://player.example.net/embed/1")
        val topLevel = collector.add(dom("https://media.example.com/b.mp4"))!!
        assertThat(topLevel.headers.referer).isEqualTo(page)
        val aboutBlankFrame = collector.add(dom("https://media.example.com/c.mp4", frameUrl = "about:blank"))!!
        assertThat(aboutBlankFrame.headers.referer).isEqualTo(page)
        val explicit = collector.add(dom("https://media.example.com/d.mp4", headers = mapOf("Referer" to "https://r.example/")))!!
        assertThat(explicit.headers.referer).isEqualTo("https://r.example/")
    }

    // ---- dedupe & merge ----

    @Test
    fun `same URL is merged and network headers win over dom ones`() {
        val url = "https://cdn.example.com/v/movie.mp4"
        collector.add(dom(url, title = "Real Title", poster = "https://img.example.com/p.jpg", duration = 600.0, width = 1920, height = 1080))
        now = 2_000L
        val merged = collector.add(
            network(
                "$url#t=5",
                mime = "video/mp4",
                size = 90_000_000,
                headers = mapOf("Referer" to "https://site.example.com/", "Cookie" to "sid=2"),
            ),
        )!!
        assertThat(collector.videos).hasSize(1)
        assertThat(merged.source).isEqualTo(DetectionSource.BOTH)
        assertThat(merged.headers.referer).isEqualTo("https://site.example.com/")
        assertThat(merged.headers.cookie).isEqualTo("sid=2")
        assertThat(merged.title).isEqualTo("Real Title")
        assertThat(merged.posterUrl).isEqualTo("https://img.example.com/p.jpg")
        assertThat(merged.durationSec).isEqualTo(600.0)
        assertThat(merged.height).isEqualTo(1080)
        assertThat(merged.sizeBytes).isEqualTo(90_000_000L)
        assertThat(merged.mimeType).isEqualTo("video/mp4")
        assertThat(merged.detectedAt).isEqualTo(1_000L)
        assertThat(merged.url).isEqualTo(url)

        // A later DOM report does not replace network headers, but fills gaps.
        val again = collector.add(dom(url, headers = mapOf("User-Agent" to "DOM UA")))!!
        assertThat(again.headers.referer).isEqualTo("https://site.example.com/")
        assertThat(again.headers.userAgent).isEqualTo("DOM UA")
        assertThat(again.source).isEqualTo(DetectionSource.BOTH)
    }

    @Test
    fun `merge keeps the largest size fills nulls and upgrades fallback titles`() {
        val url = "https://cdn.example.com/v/clip.webm"
        val first = collector.add(network(url, size = 5_000_000))!!
        assertThat(first.title).isEqualTo("Watch 42 - Site")
        val second = collector.add(network("$url?_=1700000000", size = 3_000_000, headers = mapOf("Cookie" to "new=1")))!!
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.sizeBytes).isEqualTo(5_000_000L)
        assertThat(second.headers.cookie).isEqualTo("new=1")
        assertThat(second.source).isEqualTo(DetectionSource.NETWORK)
        val third = collector.add(dom(url, title = "Element title", poster = "https://img/p.jpg"))!!
        assertThat(third.title).isEqualTo("Element title")
        assertThat(third.posterUrl).isEqualTo("https://img/p.jpg")
        val fourth = collector.add(dom(url, title = "Other title", poster = "https://img/other.jpg"))!!
        assertThat(fourth.title).isEqualTo("Element title")
        assertThat(fourth.posterUrl).isEqualTo("https://img/p.jpg")
        assertThat(collector.videos).hasSize(1)
    }

    @Test
    fun `generic dom type is upgraded by a specific network type`() {
        val url = "https://media.example.com/stream/abc"
        assertThat(collector.add(dom(url))!!.type).isEqualTo(StreamType.OTHER)
        val merged = collector.add(network(url, mime = "video/webm"))!!
        assertThat(merged.type).isEqualTo(StreamType.WEBM)
        assertThat(merged.mimeType).isEqualTo("video/webm")
    }

    @Test
    fun `cookies by host are unioned`() {
        val url = "https://e.com/a.m3u8"
        collector.add(network(url, cookiesByHost = mapOf("a.com" to "1")))
        val merged = collector.add(network(url, cookiesByHost = mapOf("b.com" to "2", "a.com" to "3")))!!
        assertThat(merged.cookiesByHost).containsExactly("a.com", "3", "b.com", "2")
    }

    @Test
    fun `videos keep first-detected order`() {
        collector.add(network("https://e.com/1.mp4"))
        collector.add(network("https://e.com/2.m3u8"))
        collector.add(network("https://e.com/1.mp4", size = 99_999_999))
        collector.add(dom("https://e.com/3.webm"))
        assertThat(collector.videos.map { it.url }).containsExactly(
            "https://e.com/1.mp4",
            "https://e.com/2.m3u8",
            "https://e.com/3.webm",
        ).inOrder()
    }

    // ---- page metadata ----

    @Test
    fun `og image is the poster fallback and og title the title fallback`() {
        collector.onPage(PageMessage(page, title = "Page title", ogTitle = "OG Title", ogImage = "/og.jpg"))
        val video = collector.add(network("https://cdn.example.com/a.m3u8"))!!
        assertThat(video.posterUrl).isEqualTo("https://site.example.com/og.jpg")
        assertThat(video.title).isEqualTo("OG Title")
        val withPoster = collector.add(dom("https://cdn.example.com/b.mp4", poster = "https://img/b.jpg", title = "B"))!!
        assertThat(withPoster.posterUrl).isEqualTo("https://img/b.jpg")
        assertThat(withPoster.title).isEqualTo("B")
    }

    @Test
    fun `onPage updates existing videos without overriding element metadata`() {
        val plain = collector.add(network("https://cdn.example.com/a.m3u8"))!!
        collector.add(dom("https://cdn.example.com/b.mp4", poster = "https://img/b.jpg", title = "B"))
        assertThat(plain.posterUrl).isNull()
        collector.onPage(PageMessage("https://site.example.com/watch/42?x=1", title = "New", ogTitle = "OG", ogImage = "https://img/og.jpg"))
        val (a, b) = collector.videos
        assertThat(a.posterUrl).isEqualTo("https://img/og.jpg")
        assertThat(a.title).isEqualTo("OG")
        assertThat(b.posterUrl).isEqualTo("https://img/b.jpg")
        assertThat(b.title).isEqualTo("B")
        assertThat(collector.pageUrl).isEqualTo("https://site.example.com/watch/42?x=1")
    }

    // ---- HLS folding ----

    @Test
    fun `variants reported after the master is parsed are folded`() {
        val master = collector.add(network("https://cdn.example.com/hls/master.m3u8"))!!
        val removed = collector.onMasterParsed(
            master.id,
            listOf("https://cdn.example.com/hls/720p.m3u8", "https://cdn.example.com/hls/audio/en.m3u8"),
        )
        assertThat(removed).isEmpty()
        assertThat(collector.add(network("https://cdn.example.com/hls/720p.m3u8"))).isNull()
        assertThat(collector.add(network("https://cdn.example.com/hls/audio/en.m3u8?_=123"))).isNull()
        assertThat(collector.videos.map { it.id }).containsExactly(master.id)
        assertThat(collector.add(network("https://cdn.example.com/hls/other.m3u8"))).isNotNull()
    }

    @Test
    fun `variants listed before the master is parsed are removed`() {
        val master = collector.add(network("https://cdn.example.com/hls/master.m3u8"))!!
        val v720 = collector.add(network("https://cdn.example.com/hls/720p.m3u8"))!!
        val v360 = collector.add(network("https://cdn.example.com/hls/360p.m3u8"))!!
        val unrelated = collector.add(network("https://cdn.example.com/other.mp4"))!!
        val removed = collector.onMasterParsed(
            master.id,
            listOf(
                "https://cdn.example.com/hls/720p.m3u8",
                "https://cdn.example.com/hls/360p.m3u8",
                "https://cdn.example.com/hls/1080p.m3u8",
                "https://cdn.example.com/hls/master.m3u8",
            ),
        )
        assertThat(removed).containsExactly(v720.id, v360.id).inOrder()
        assertThat(collector.videos.map { it.id }).containsExactly(master.id, unrelated.id).inOrder()
        assertThat(collector.add(network("https://cdn.example.com/hls/1080p.m3u8"))).isNull()
        assertThat(collector.add(network("https://cdn.example.com/hls/master.m3u8"))).isNotNull()
    }

    @Test
    fun `enrichment that landed on a variant moves to the master when folded`() {
        val master = collector.add(network("https://cdn.example.com/hls/master.m3u8"))!!
        val variant = collector.add(network("https://cdn.example.com/hls/720p.m3u8"))!!
        collector.add(dom("blob:https://site.example.com/1", isBlob = true, title = "Show", poster = "https://img/s.jpg", duration = 1500.0))
        assertThat(collector.videos.first { it.id == variant.id }.title).isEqualTo("Show")
        collector.onMasterParsed(master.id, listOf(variant.url))
        val updated = collector.videos.single()
        assertThat(updated.title).isEqualTo("Show")
        assertThat(updated.posterUrl).isEqualTo("https://img/s.jpg")
        assertThat(updated.durationSec).isEqualTo(1500.0)
    }

    // ---- blob enrichment ----

    @Test
    fun `blob dom report enriches the most recent adaptive video`() {
        collector.add(network("https://cdn.example.com/a/master.m3u8"))
        val mp4 = collector.add(network("https://cdn.example.com/movie.mp4"))!!
        val dash = collector.add(network("https://cdn.example.com/b/manifest.mpd"))!!
        val result = collector.add(
            dom("blob:https://site.example.com/uuid", isBlob = true, title = "Episode 1", poster = "https://img/e1.jpg", duration = 1320.5, width = 1280, height = 720),
        )
        assertThat(result).isNull()
        val enriched = collector.videos.first { it.id == dash.id }
        assertThat(enriched.title).isEqualTo("Episode 1")
        assertThat(enriched.posterUrl).isEqualTo("https://img/e1.jpg")
        assertThat(enriched.durationSec).isEqualTo(1320.5)
        assertThat(enriched.height).isEqualTo(720)
        assertThat(collector.videos.first { it.id == mp4.id }.posterUrl).isNull()
        assertThat(collector.videos.first().title).isEqualTo("Watch 42 - Site")
        assertThat(collector.videos).hasSize(3)
    }

    @Test
    fun `blob enrichment only fills missing values`() {
        val hls = collector.add(network("https://cdn.example.com/a/master.m3u8"))!!
        collector.update(hls.id) { it.copy(durationSec = 100.0) }
        collector.add(dom("blob:https://site.example.com/1", isBlob = true, title = "First", duration = 50.0))
        collector.add(dom("blob:https://site.example.com/1", isBlob = true, title = "Second", poster = "https://img/p.jpg"))
        val video = collector.videos.single()
        assertThat(video.title).isEqualTo("First")
        assertThat(video.durationSec).isEqualTo(100.0)
        assertThat(video.posterUrl).isEqualTo("https://img/p.jpg")
    }

    @Test
    fun `blob enrichment is kept until an adaptive video arrives`() {
        collector.add(dom("blob:https://site.example.com/1", isBlob = true, poster = "https://img/p.jpg"))
        collector.add(dom("blob:https://site.example.com/1", isBlob = true, title = "Pending title", duration = 42.0))
        val mp4 = collector.add(network("https://cdn.example.com/movie.mp4"))!!
        assertThat(mp4.title).isEqualTo("Watch 42 - Site")
        assertThat(mp4.posterUrl).isNull()
        val hls = collector.add(network("https://cdn.example.com/master.m3u8"))!!
        assertThat(hls.title).isEqualTo("Pending title")
        assertThat(hls.posterUrl).isEqualTo("https://img/p.jpg")
        assertThat(hls.durationSec).isEqualTo(42.0)
        val nextHls = collector.add(network("https://cdn.example.com/other.m3u8"))!!
        assertThat(nextHls.title).isEqualTo("Watch 42 - Site")
    }

    @Test
    fun `blob urls are detected even without the isBlob flag and never listed`() {
        assertThat(collector.add(dom("blob:https://site.example.com/xyz", title = "T"))).isNull()
        assertThat(collector.videos).isEmpty()
        assertThat(collector.add(network("blob:https://site.example.com/xyz"))).isNull()
    }

    // ---- subtitles ----

    @Test
    fun `track elements become tab subtitles attached to every video`() {
        val first = collector.add(network("https://cdn.example.com/a.m3u8"))!!
        assertThat(first.subtitles).isEmpty()
        collector.add(
            dom(
                "https://cdn.example.com/b.mp4",
                tracks = listOf(
                    TrackReport("https://cdn.example.com/subs/en.vtt", kind = "subtitles", lang = "en", label = "English"),
                    TrackReport("/subs/de.srt", kind = "captions", lang = "de"),
                    TrackReport("https://cdn.example.com/chapters.vtt", kind = "chapters"),
                    TrackReport("https://cdn.example.com/subs/plain", kind = null),
                    TrackReport("https://cdn.example.com/subs/segment12.vtt", kind = "subtitles"),
                ),
            ),
        )
        val en = SubtitleTrack(
            id = UrlUtils.stableId("https://cdn.example.com/subs/en.vtt"),
            url = "https://cdn.example.com/subs/en.vtt",
            label = "English",
            language = "en",
            format = SubtitleFormat.VTT,
        )
        val de = SubtitleTrack(
            id = UrlUtils.stableId("https://site.example.com/subs/de.srt"),
            url = "https://site.example.com/subs/de.srt",
            language = "de",
            format = SubtitleFormat.SRT,
        )
        val plain = SubtitleTrack(
            id = UrlUtils.stableId("https://cdn.example.com/subs/plain"),
            url = "https://cdn.example.com/subs/plain",
            format = SubtitleFormat.VTT,
        )
        collector.videos.forEach { assertThat(it.subtitles).containsExactly(en, de, plain).inOrder() }

        val later = collector.add(network("https://cdn.example.com/c.mp4"))!!
        assertThat(later.subtitles).containsExactly(en, de, plain).inOrder()
    }

    @Test
    fun `subtitle reports are collected and segment-like ones ignored`() {
        collector.add(network("https://cdn.example.com/a.m3u8"))
        assertThat(collector.add(network("https://cdn.example.com/subs/fr.vtt", mime = "text/vtt", resourceType = "xmlhttprequest"))).isNull()
        assertThat(collector.add(network("https://cdn.example.com/subs/x", mime = "application/x-subrip", title = "Spanish"))).isNull()
        assertThat(collector.add(network("https://cdn.example.com/hls/subs/fileSequence3.webvtt", mime = "text/vtt"))).isNull()
        assertThat(collector.add(network("https://cdn.example.com/hls/subs/00042.vtt"))).isNull()
        val subtitles = collector.videos.single().subtitles
        assertThat(subtitles.map { it.url }).containsExactly(
            "https://cdn.example.com/subs/fr.vtt",
            "https://cdn.example.com/subs/x",
        ).inOrder()
        assertThat(subtitles[0].format).isEqualTo(SubtitleFormat.VTT)
        assertThat(subtitles[0].label).isEqualTo("fr.vtt")
        assertThat(subtitles[1].format).isEqualTo(SubtitleFormat.SRT)
        assertThat(subtitles[1].label).isEqualTo("Spanish")
    }

    @Test
    fun `addSubtitle merges duplicates and keeps video specific tracks`() {
        val hls = collector.add(network("https://cdn.example.com/a.m3u8"))!!
        val own = SubtitleTrack("own", "https://cdn.example.com/a/sub.m3u8", label = "Manifest", format = SubtitleFormat.VTT)
        collector.update(hls.id) { it.copy(subtitles = listOf(own)) }
        val tab = SubtitleTrack(UrlUtils.stableId("https://e.com/s.vtt"), "https://e.com/s.vtt", format = SubtitleFormat.VTT)
        collector.addSubtitle(tab)
        collector.addSubtitle(tab.copy(label = "Label", language = "en"))
        collector.addSubtitle(tab.copy(label = "Ignored"))
        val subtitles = collector.videos.single().subtitles
        assertThat(subtitles).containsExactly(own, tab.copy(label = "Label", language = "en")).inOrder()

        // update() keeps the tab subtitles even if the transform drops them.
        collector.update(hls.id) { it.copy(subtitles = emptyList()) }
        assertThat(collector.videos.single().subtitles).containsExactly(tab.copy(label = "Label", language = "en"))
    }

    @Test
    fun `tracks of a blob element are still collected`() {
        collector.add(network("https://cdn.example.com/a.mpd"))
        collector.add(
            dom(
                "blob:https://site.example.com/1",
                isBlob = true,
                tracks = listOf(TrackReport("https://cdn.example.com/en.vtt", kind = "subtitles", lang = "en")),
            ),
        )
        assertThat(collector.videos.single().subtitles.map { it.language }).containsExactly("en")
    }

    // ---- DRM ----

    @Test
    fun `markDrm applies to current and future videos`() {
        val before = collector.add(network("https://cdn.example.com/a.mpd"))!!
        val ownDrm = DrmInfo(system = "Widevine", reason = "DASH ContentProtection")
        collector.update(before.id) { it.copy(drm = ownDrm) }
        val plain = collector.add(network("https://cdn.example.com/b.mp4"))!!
        assertThat(plain.isDrmProtected).isFalse()

        val eme = DrmInfo(system = "com.widevine.alpha", reason = "EME request")
        collector.markDrm(eme)
        assertThat(collector.videos.first { it.id == before.id }.drm).isEqualTo(ownDrm)
        assertThat(collector.videos.first { it.id == plain.id }.drm).isEqualTo(eme)
        val after = collector.add(network("https://cdn.example.com/c.m3u8"))!!
        assertThat(after.drm).isEqualTo(eme)
        assertThat(collector.update(after.id) { it.copy(drm = null) }!!.drm).isEqualTo(eme)
    }

    @Test
    fun `a more specific DRM signal replaces an unspecific one`() {
        collector.markDrm(DrmInfo(system = null, reason = "encrypted event"))
        collector.markDrm(DrmInfo(system = "com.widevine.alpha", reason = "EME request"))
        collector.markDrm(DrmInfo(system = "com.microsoft.playready", reason = "EME request"))
        assertThat(collector.add(network("https://cdn.example.com/c.m3u8"))!!.drm!!.system).isEqualTo("com.widevine.alpha")
    }

    // ---- update ----

    @Test
    fun `update applies inspection results and keeps identity`() {
        val hls = collector.add(network("https://cdn.example.com/master.m3u8"))!!
        val variants = listOf(VideoVariant("https://cdn.example.com/720.m3u8", bandwidth = 2_000_000, width = 1280, height = 720))
        val updated = collector.update(hls.id) {
            it.copy(id = "hijack", tabId = "other", variants = variants, isMasterPlaylist = true, hlsFragmentedMp4 = true, isLive = true)
        }!!
        assertThat(updated.id).isEqualTo(hls.id)
        assertThat(updated.tabId).isEqualTo("tab-1")
        assertThat(updated.variants).isEqualTo(variants)
        assertThat(updated.isMasterPlaylist).isTrue()
        assertThat(updated.isLive).isTrue()
        assertThat(collector.videos.single()).isEqualTo(updated)
        assertThat(collector.update("unknown") { it }).isNull()
    }

    // ---- reset ----

    @Test
    fun `reset forgets everything`() {
        val master = collector.add(network("https://cdn.example.com/master.m3u8"))!!
        collector.onMasterParsed(master.id, listOf("https://cdn.example.com/720.m3u8"))
        collector.addSubtitle(SubtitleTrack("s", "https://e.com/s.vtt"))
        collector.markDrm(DrmInfo(reason = "EME request"))
        collector.onPage(PageMessage(page, title = "T", ogImage = "https://img/og.jpg"))
        collector.add(dom("blob:https://site.example.com/1", isBlob = true, title = "Pending"))

        collector.reset("https://next.example.com/")
        assertThat(collector.videos).isEmpty()
        assertThat(collector.pageUrl).isEqualTo("https://next.example.com/")

        now = 9_000L
        val variant = collector.add(network("https://cdn.example.com/720.m3u8"))!!
        assertThat(variant.drm).isNull()
        assertThat(variant.subtitles).isEmpty()
        assertThat(variant.posterUrl).isNull()
        assertThat(variant.title).isEqualTo("Watch 42 - Site")
        assertThat(variant.detectedAt).isEqualTo(9_000L)
        assertThat(collector.add(network("https://cdn.example.com/master.m3u8"))!!.title).isEqualTo("Watch 42 - Site")
    }

    @Test
    fun `reset with null page URL`() {
        collector.add(network("https://cdn.example.com/a.mp4"))
        collector.reset(null)
        assertThat(collector.pageUrl).isNull()
        assertThat(collector.videos).isEmpty()
    }
}
