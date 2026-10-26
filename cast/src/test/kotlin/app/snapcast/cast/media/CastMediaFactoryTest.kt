package app.snapcast.cast.media

import app.snapcast.cast.HlsSegmentFormat
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.proxy.LocalSource
import app.snapcast.proxy.ProxiedStream
import app.snapcast.proxy.ProxiedSubtitle
import app.snapcast.proxy.ProxyStreamRequest
import app.snapcast.proxy.SubtitleSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CastMediaFactoryTest {

    private val english = SubtitleTrack(id = "en", url = "https://media.example.com/en.vtt", label = "English", language = "en")
    private val french = SubtitleTrack(id = "fr", url = "https://subs.example.org/fr.srt", language = "fr", format = SubtitleFormat.SRT)
    private val ttml = SubtitleTrack(id = "de", url = "https://media.example.com/de.ttml", language = "de")
    private val blob = SubtitleTrack(id = "blob", url = "blob:https://www.example.com/5f2c", language = "es")

    private val hls = DetectedVideo(
        id = "v1",
        tabId = "t1",
        url = "https://media.example.com/master.m3u8",
        type = StreamType.HLS,
        title = "Big Buck Bunny",
        headers = MediaHeaders(referer = "https://www.example.com/watch", cookie = "sid=42"),
        cookiesByHost = mapOf("subs.example.org" to "sub=1"),
        durationSec = 596.5,
        posterUrl = "https://media.example.com/poster.jpg",
        subtitles = listOf(english, french, blob, ttml),
        hlsFragmentedMp4 = true,
    )

    @Test
    fun `proxied media registers the stream with its headers and uses the proxy URL`() {
        val proxy = FakeMediaProxy()
        val media = CastMediaFactory.proxied(hls, variant = null, subtitle = null, proxy = proxy)

        val request = proxy.streams.single()
        assertThat(request.url).isEqualTo(hls.url)
        assertThat(request.headers).containsEntry("Referer", "https://www.example.com/watch")
        assertThat(request.headers).containsEntry("Cookie", "sid=42")
        assertThat(request.cookiesByHost).isEqualTo(hls.cookiesByHost)
        assertThat(request.typeHint).isEqualTo(StreamType.HLS)
        assertThat(media.contentUrl).isEqualTo("http://192.168.1.20:8642/stream/s1/index.m3u8")
        assertThat(media.contentType).isEqualTo("application/x-mpegURL")
        assertThat(media.hlsSegmentFormat).isEqualTo(HlsSegmentFormat.FMP4)
        assertThat(media.title).isEqualTo("Big Buck Bunny")
        assertThat(media.durationMs).isEqualTo(596_500L)
        assertThat(media.originalUrl).isEqualTo(hls.url)
    }

    @Test
    fun `a chosen variant is registered instead of the master playlist`() {
        val proxy = FakeMediaProxy()
        val variant = VideoVariant(url = "https://media.example.com/720p.m3u8", height = 720)
        val media = CastMediaFactory.proxied(hls, variant, subtitle = null, proxy = proxy)

        assertThat(proxy.streams.single().url).isEqualTo(variant.url)
        assertThat(media.originalUrl).isEqualTo(hls.url)
    }

    @Test
    fun `a variant with separate audio renditions casts the master playlist`() {
        val variant = VideoVariant(url = "https://media.example.com/v5/prog_index.m3u8", height = 1080, separateAudio = true)
        val proxy = FakeMediaProxy()
        CastMediaFactory.proxied(hls, variant, subtitle = null, proxy = proxy)

        assertThat(proxy.streams.single().url).isEqualTo(hls.url)
        assertThat(CastMediaFactory.direct(hls, variant, subtitle = null).contentUrl).isEqualTo(hls.url)
    }

    @Test
    fun `a variant on another host gets the captured cookie only for the master host`() {
        val video = hls.copy(
            headers = MediaHeaders(
                referer = "https://www.example.com/watch",
                cookie = "sid=42",
                extra = mapOf("Authorization" to "Bearer t"),
            ),
        )
        val variant = VideoVariant(url = "https://cdn.other.net/720p.m3u8", height = 720)
        val proxy = FakeMediaProxy()
        CastMediaFactory.proxied(video, variant, subtitle = null, proxy = proxy)

        val request = proxy.streams.single()
        assertThat(request.url).isEqualTo(variant.url)
        assertThat(request.headers.keys.map { it.lowercase() }).containsNoneOf("cookie", "authorization")
        assertThat(request.headers).containsEntry("Referer", "https://www.example.com/watch")
        assertThat(request.cookiesByHost).containsEntry("media.example.com", "sid=42")
        assertThat(request.cookiesByHost).containsEntry("subs.example.org", "sub=1")
    }

    @Test
    fun `HLS subtitle renditions are not side-loaded and keep the other ids`() {
        val rendition = SubtitleTrack(
            id = "r",
            url = "https://media.example.com/s1/en/prog_index.m3u8",
            label = "English",
            language = "en",
            format = SubtitleFormat.VTT,
            isHlsRendition = true,
        )
        val video = hls.copy(subtitles = listOf(rendition, english))
        val proxy = FakeMediaProxy()

        val proxied = CastMediaFactory.proxied(video, variant = null, subtitle = rendition, proxy = proxy)
        assertThat(proxy.subtitles.filterIsInstance<SubtitleSource.Remote>().map { it.url }).containsExactly(english.url)
        assertThat(proxied.textTracks.map { it.trackId }).containsExactly(2L)
        assertThat(proxied.activeTextTrackId).isNull()

        val direct = CastMediaFactory.direct(video, variant = null, subtitle = rendition)
        assertThat(direct.textTracks.map { it.trackId }).containsExactly(2L)
        assertThat(direct.activeTextTrackId).isNull()
        assertThat(CastMediaRules.isProxyableSubtitle(rendition)).isFalse()
        assertThat(CastMediaRules.isDirectSubtitle(rendition)).isFalse()
    }

    @Test
    fun `subtitle tracks get stable ids and unusable ones are skipped`() {
        val proxy = FakeMediaProxy()
        val media = CastMediaFactory.proxied(hls, variant = null, subtitle = french, proxy = proxy)

        assertThat(media.textTracks.map { it.trackId }).containsExactly(1L, 2L, 4L).inOrder()
        assertThat(media.textTracks.map { it.url })
            .containsExactly(
                "http://192.168.1.20:8642/sub/sub1.vtt",
                "http://192.168.1.20:8642/sub/sub2.vtt",
                "http://192.168.1.20:8642/sub/sub3.vtt",
            )
            .inOrder()
        assertThat(media.textTracks[0].name).isEqualTo("English")
        assertThat(media.textTracks[1].language).isEqualTo("fr")
        assertThat(media.textTracks[2].name).isEqualTo("de")
        assertThat(media.activeTextTrackId).isEqualTo(2L)
    }

    @Test
    fun `subtitles are fetched with the cookie of their own host`() {
        val proxy = FakeMediaProxy()
        CastMediaFactory.proxied(hls, variant = null, subtitle = null, proxy = proxy)

        val sources = proxy.subtitles.filterIsInstance<SubtitleSource.Remote>()
        val sameHost = sources.single { it.url == english.url }
        val otherHost = sources.single { it.url == french.url }
        assertThat(sameHost.headers).containsEntry("Cookie", "sid=42")
        assertThat(sameHost.format).isEqualTo(SubtitleFormat.UNKNOWN)
        assertThat(otherHost.headers).containsEntry("Cookie", "sub=1")
        assertThat(otherHost.headers).containsEntry("Referer", "https://www.example.com/watch")
        assertThat(otherHost.format).isEqualTo(SubtitleFormat.SRT)
        assertThat(sources.single { it.url == ttml.url }.format).isEqualTo(SubtitleFormat.UNKNOWN)
    }

    @Test
    fun `a chosen subtitle outside the list is appended with the next id`() {
        val extra = SubtitleTrack(id = "x", url = "https://media.example.com/extra.vtt")
        val media = CastMediaFactory.proxied(hls, variant = null, subtitle = extra, proxy = FakeMediaProxy())

        assertThat(media.textTracks.map { it.trackId }).containsExactly(1L, 2L, 4L, 5L).inOrder()
        assertThat(media.activeTextTrackId).isEqualTo(5L)
    }

    @Test
    fun `local subtitles use the provided inline source`() {
        val local = SubtitleTrack(id = "local", url = "content://docs/subs.srt", isLocal = true)
        val video = hls.copy(subtitles = listOf(local))
        val inline = SubtitleSource.Inline("WEBVTT\n", SubtitleFormat.VTT)
        val proxy = FakeMediaProxy()

        val media = CastMediaFactory.proxied(video, null, local, proxy, mapOf("local" to inline))

        assertThat(proxy.subtitles).containsExactly(inline)
        assertThat(media.activeTextTrackId).isEqualTo(1L)
    }

    @Test
    fun `direct media keeps the original URL and only VTT subtitles`() {
        val media = CastMediaFactory.direct(hls, variant = null, subtitle = english)

        assertThat(media.contentUrl).isEqualTo(hls.url)
        assertThat(media.textTracks.map { it.trackId }).containsExactly(1L)
        assertThat(media.textTracks.single().url).isEqualTo(english.url)
        assertThat(media.activeTextTrackId).isEqualTo(1L)
    }

    @Test
    fun `direct media with a variant loads the variant`() {
        val variant = VideoVariant(url = "https://media.example.com/1080p.m3u8", height = 1080)
        val media = CastMediaFactory.direct(hls, variant, subtitle = french)

        assertThat(media.contentUrl).isEqualTo(variant.url)
        assertThat(media.activeTextTrackId).isNull()
    }

    @Test
    fun `live and progressive streams`() {
        val live = hls.copy(isLive = true, hlsFragmentedMp4 = false)
        val liveMedia = CastMediaFactory.direct(live, null, null)
        assertThat(liveMedia.isLive).isTrue()
        assertThat(liveMedia.durationMs).isNull()
        assertThat(liveMedia.hlsSegmentFormat).isEqualTo(HlsSegmentFormat.TS)

        val mp4 = DetectedVideo(
            id = "v2",
            tabId = "t1",
            url = "https://media.example.com/clip.mp4",
            type = StreamType.MP4,
            mimeType = "video/mp4",
            posterUrl = "data:image/png;base64,AAAA",
        )
        val mp4Media = CastMediaFactory.direct(mp4, null, null)
        assertThat(mp4Media.contentType).isEqualTo("video/mp4")
        assertThat(mp4Media.hlsSegmentFormat).isNull()
        assertThat(mp4Media.posterUrl).isNull()
    }

    private class FakeMediaProxy : MediaProxy {
        val streams = mutableListOf<ProxyStreamRequest>()
        val subtitles = mutableListOf<SubtitleSource>()

        override fun registerStream(request: ProxyStreamRequest): ProxiedStream {
            streams += request
            val id = "s${streams.size}"
            return ProxiedStream(id, "/stream/$id/index.m3u8")
        }

        override fun registerSubtitle(source: SubtitleSource): ProxiedSubtitle {
            subtitles += source
            val id = "sub${subtitles.size}"
            return ProxiedSubtitle(id, "/sub/$id.vtt")
        }

        override fun registerLocal(source: LocalSource, fileNameHint: String): ProxiedStream =
            ProxiedStream("l1", "/local/l1/$fileNameHint")

        override fun url(path: String): String = "http://192.168.1.20:8642$path"
    }
}
