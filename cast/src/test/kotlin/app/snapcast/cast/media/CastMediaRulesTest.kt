package app.snapcast.cast.media

import app.snapcast.cast.CastTextTrack
import app.snapcast.cast.HlsSegmentFormat
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CastMediaRulesTest {

    @Test
    fun `HLS always uses the Default Media Receiver content type`() {
        assertThat(CastMediaRules.contentType(StreamType.HLS, null)).isEqualTo("application/x-mpegURL")
        assertThat(CastMediaRules.contentType(StreamType.HLS, "application/vnd.apple.mpegurl"))
            .isEqualTo("application/x-mpegURL")
    }

    @Test
    fun `DASH uses the dash content type`() {
        assertThat(CastMediaRules.contentType(StreamType.DASH, "application/octet-stream"))
            .isEqualTo("application/dash+xml")
    }

    @Test
    fun `progressive streams prefer the server mime type without parameters`() {
        assertThat(CastMediaRules.contentType(StreamType.MP4, "video/mp4; codecs=\"avc1\"")).isEqualTo("video/mp4")
        assertThat(CastMediaRules.contentType(StreamType.WEBM, "video/webm")).isEqualTo("video/webm")
    }

    @Test
    fun `progressive streams fall back to the type default for unusable mime types`() {
        assertThat(CastMediaRules.contentType(StreamType.MP4, null)).isEqualTo("video/mp4")
        assertThat(CastMediaRules.contentType(StreamType.WEBM, "application/octet-stream")).isEqualTo("video/webm")
        assertThat(CastMediaRules.contentType(StreamType.MKV, "  ")).isEqualTo("video/x-matroska")
    }

    @Test
    fun `unknown streams are inferred from the mime type`() {
        assertThat(CastMediaRules.contentType(StreamType.OTHER, "application/vnd.apple.mpegurl"))
            .isEqualTo("application/x-mpegURL")
        assertThat(CastMediaRules.contentType(StreamType.OTHER, "application/dash+xml"))
            .isEqualTo("application/dash+xml")
        assertThat(CastMediaRules.contentType(StreamType.OTHER, "video/quicktime")).isEqualTo("video/quicktime")
        assertThat(CastMediaRules.contentType(StreamType.OTHER, "video/*")).isEqualTo("video/mp4")
        assertThat(CastMediaRules.contentType(StreamType.OTHER, null)).isEqualTo("video/mp4")
    }

    @Test
    fun `local files use a concrete provider mime type or the extension`() {
        assertThat(CastMediaRules.localMimeType("clip.mkv", "video/x-matroska")).isEqualTo("video/x-matroska")
        assertThat(CastMediaRules.localMimeType("clip.MKV", "application/octet-stream")).isEqualTo("video/x-matroska")
        assertThat(CastMediaRules.localMimeType("song.mp3", null)).isEqualTo("audio/mpeg")
        assertThat(CastMediaRules.localMimeType("video", "video/*")).isEqualTo("video/mp4")
        assertThat(CastMediaRules.localMimeType(null, null)).isEqualTo("video/mp4")
    }

    @Test
    fun `HLS segment format follows the fragmented MP4 flag`() {
        assertThat(CastMediaRules.hlsSegmentFormat(StreamType.HLS, true)).isEqualTo(HlsSegmentFormat.FMP4)
        assertThat(CastMediaRules.hlsSegmentFormat(StreamType.HLS, false)).isEqualTo(HlsSegmentFormat.TS)
        assertThat(CastMediaRules.hlsSegmentFormat(StreamType.HLS, null)).isNull()
    }

    @Test
    fun `non HLS streams have no segment format`() {
        assertThat(CastMediaRules.hlsSegmentFormat(StreamType.DASH, true)).isNull()
        assertThat(CastMediaRules.hlsSegmentFormat(StreamType.MP4, false)).isNull()
    }

    @Test
    fun `streams without credentials can be cast directly`() {
        val video = video(headers = MediaHeaders(referer = "https://example.com/", userAgent = "UA"))
        assertThat(CastMediaRules.canCastDirectly(video)).isTrue()
    }

    @Test
    fun `cookies prevent direct casting`() {
        assertThat(CastMediaRules.canCastDirectly(video(headers = MediaHeaders(cookie = "session=1")))).isFalse()
        assertThat(CastMediaRules.canCastDirectly(video(cookiesByHost = mapOf("cdn.example.com" to "a=b")))).isFalse()
    }

    @Test
    fun `authorization header prevents direct casting whatever its case`() {
        val video = video(headers = MediaHeaders(extra = mapOf("authorization" to "Bearer x")))
        assertThat(CastMediaRules.canCastDirectly(video)).isFalse()
        val other = video(headers = MediaHeaders(extra = mapOf("X-Requested-With" to "XMLHttpRequest")))
        assertThat(CastMediaRules.canCastDirectly(other)).isTrue()
    }

    @Test
    fun `only plain http VTT subtitles are direct`() {
        assertThat(CastMediaRules.isDirectSubtitle(track("https://e.com/a.vtt"))).isTrue()
        assertThat(CastMediaRules.isDirectSubtitle(track("https://e.com/a?id=1", SubtitleFormat.VTT))).isTrue()
        assertThat(CastMediaRules.isDirectSubtitle(track("https://e.com/a.srt"))).isFalse()
        assertThat(CastMediaRules.isDirectSubtitle(track("content://media/a.vtt"))).isFalse()
        assertThat(CastMediaRules.isDirectSubtitle(track("https://e.com/a.vtt").copy(isLocal = true))).isFalse()
    }

    @Test
    fun `any remote http subtitle can be proxied`() {
        assertThat(CastMediaRules.isProxyableSubtitle(track("https://e.com/a.srt"))).isTrue()
        assertThat(CastMediaRules.isProxyableSubtitle(track("https://e.com/a", SubtitleFormat.UNKNOWN))).isTrue()
        assertThat(CastMediaRules.isProxyableSubtitle(track("https://e.com/a.ttml"))).isTrue()
        assertThat(CastMediaRules.isProxyableSubtitle(track("blob:https://e.com/1234"))).isFalse()
        assertThat(CastMediaRules.isProxyableSubtitle(track("https://e.com/a.vtt").copy(isLocal = true))).isFalse()
    }

    @Test
    fun `subtitle format comes from the declaration or the URL`() {
        assertThat(CastMediaRules.subtitleFormat(track("https://e.com/a.srt?x=1"))).isEqualTo(SubtitleFormat.SRT)
        assertThat(CastMediaRules.subtitleFormat(track("https://e.com/subs/a.VTT#t"))).isEqualTo(SubtitleFormat.VTT)
        assertThat(CastMediaRules.subtitleFormat(track("https://e.com/a.dfxp"))).isEqualTo(SubtitleFormat.TTML)
        assertThat(CastMediaRules.subtitleFormat(track("https://e.com/a", SubtitleFormat.SRT))).isEqualTo(SubtitleFormat.SRT)
        assertThat(CastMediaRules.subtitleFormat(track("https://e.com/subtitles"))).isEqualTo(SubtitleFormat.UNKNOWN)
    }

    @Test
    fun `next track id follows the highest id`() {
        assertThat(CastMediaRules.nextTrackId(emptyList())).isEqualTo(1L)
        assertThat(CastMediaRules.nextTrackId(listOf(1L, 2L, 3L))).isEqualTo(4L)
        assertThat(CastMediaRules.nextTrackId(listOf(5L, 2L))).isEqualTo(6L)
    }

    @Test
    fun `track names fall back to the language and the id`() {
        assertThat(CastMediaRules.trackName(track("https://e.com/a.vtt").copy(label = "English"), 1)).isEqualTo("English")
        assertThat(CastMediaRules.trackName(track("https://e.com/a.vtt").copy(language = "fr"), 2)).isEqualTo("fr")
        assertThat(CastMediaRules.trackName(track("https://e.com/a.vtt"), 3)).isEqualTo("Subtitles 3")
    }

    private fun video(
        headers: MediaHeaders = MediaHeaders.NONE,
        cookiesByHost: Map<String, String> = emptyMap(),
    ) = DetectedVideo(
        id = "v1",
        tabId = "t1",
        url = "https://media.example.com/video.mp4",
        type = StreamType.MP4,
        headers = headers,
        cookiesByHost = cookiesByHost,
    )

    private fun track(url: String, format: SubtitleFormat = SubtitleFormat.UNKNOWN) =
        SubtitleTrack(id = url, url = url, format = format)

    @Test
    fun `an HLS subtitle rendition maps to the receiver's in-band track`() {
        val chosen = SubtitleTrack(
            id = "r",
            url = "https://e.com/subs/en.m3u8",
            label = "English (CC)",
            language = "en",
            isHlsRendition = true,
        )
        val sideLoaded = CastTextTrack(id = 1, name = "English", language = "en")
        val french = CastTextTrack(id = 7, name = "Français", language = "fr")
        val english = CastTextTrack(id = 8, name = "English", language = "en-US")
        val englishCc = CastTextTrack(id = 9, name = "English (CC)", language = "en")

        assertThat(CastMediaRules.inBandTextTrack(chosen, listOf(sideLoaded, french, english, englishCc), setOf(1L)))
            .isEqualTo(9L)
        assertThat(CastMediaRules.inBandTextTrack(chosen, listOf(sideLoaded, french, english), setOf(1L)))
            .isEqualTo(8L)
        assertThat(CastMediaRules.inBandTextTrack(chosen, listOf(sideLoaded, french), setOf(1L))).isNull()
        val unnamed = chosen.copy(language = null)
        assertThat(CastMediaRules.inBandTextTrack(unnamed, listOf(french, englishCc), emptySet())).isEqualTo(9L)
    }
}
