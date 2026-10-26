package app.snapcast.player

import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRequestCodecTest {

    @Test
    fun requestSurvivesTheRoundTrip() {
        val request = PlaybackRequest(
            url = "https://cdn.example/live/master.m3u8?token=abc",
            title = "Live: \"Launch\" ünicode",
            type = StreamType.HLS,
            mimeType = "application/vnd.apple.mpegurl",
            headers = mapOf("Referer" to "https://site.example/", "Cookie" to "a=1; b=2"),
            cookiesByHost = mapOf("seg.cdn.example" to "edge=1"),
            subtitles = listOf(
                SubtitleTrack(id = "s1", url = "https://cdn.example/en.vtt", label = "English", language = "en", format = SubtitleFormat.VTT),
                SubtitleTrack(id = "s2", url = "content://picker/7", format = SubtitleFormat.SRT, isLocal = true),
            ),
            posterUrl = "https://cdn.example/poster.jpg",
            startPositionMs = 12_345L,
            pageUrl = "https://site.example/watch/1",
            isPrivate = true,
        )

        assertThat(PlaybackRequestCodec.decode(PlaybackRequestCodec.encode(request))).isEqualTo(request)
    }

    @Test
    fun minimalRequestUsesDefaults() {
        val decoded = PlaybackRequestCodec.decode("""{"url":"https://cdn.example/v.mp4","title":"V","type":"MP4"}""")

        assertThat(decoded).isEqualTo(PlaybackRequest(url = "https://cdn.example/v.mp4", title = "V", type = StreamType.MP4))
    }

    @Test
    fun unknownKeysAreIgnored() {
        val decoded = PlaybackRequestCodec.decode("""{"url":"https://cdn.example/v.mp4","title":"V","type":"MP4","future":1}""")

        assertThat(decoded?.url).isEqualTo("https://cdn.example/v.mp4")
    }

    @Test
    fun missingOrInvalidInputGivesNull() {
        assertThat(PlaybackRequestCodec.decode(null)).isNull()
        assertThat(PlaybackRequestCodec.decode("")).isNull()
        assertThat(PlaybackRequestCodec.decode("not json")).isNull()
        assertThat(PlaybackRequestCodec.decode("""{"title":"no url","type":"MP4"}""")).isNull()
        assertThat(PlaybackRequestCodec.decode("""{"url":"https://cdn.example/v","title":"V","type":"BLURAY"}""")).isNull()
        assertThat(PlaybackRequestCodec.decode("""{"url":"  ","title":"V","type":"MP4"}""")).isNull()
    }
}
