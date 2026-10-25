package app.snapcast.player.media

import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerMimeTypesTest {

    @Test
    fun adaptiveStreamsUseMedia3ManifestTypes() {
        assertThat(PlayerMimeTypes.forStream(StreamType.HLS, null)).isEqualTo("application/x-mpegURL")
        assertThat(PlayerMimeTypes.forStream(StreamType.HLS, "application/vnd.apple.mpegurl")).isEqualTo("application/x-mpegURL")
        assertThat(PlayerMimeTypes.forStream(StreamType.DASH, null)).isEqualTo("application/dash+xml")
        assertThat(PlayerMimeTypes.forStream(StreamType.DASH, "application/octet-stream")).isEqualTo("application/dash+xml")
    }

    @Test
    fun adaptiveContentTypeWinsOverProgressiveType() {
        assertThat(PlayerMimeTypes.forStream(StreamType.OTHER, "application/vnd.apple.mpegurl; charset=utf-8"))
            .isEqualTo("application/x-mpegURL")
        assertThat(PlayerMimeTypes.forStream(StreamType.MP4, "application/dash+xml")).isEqualTo("application/dash+xml")
    }

    @Test
    fun progressiveStreamsKeepConcreteMediaTypes() {
        assertThat(PlayerMimeTypes.forStream(StreamType.MP4, "video/mp4")).isEqualTo("video/mp4")
        assertThat(PlayerMimeTypes.forStream(StreamType.MP4, "Video/MP4; codecs=\"avc1.42E01E\"")).isEqualTo("video/mp4")
        assertThat(PlayerMimeTypes.forStream(StreamType.WEBM, "video/webm")).isEqualTo("video/webm")
        assertThat(PlayerMimeTypes.forStream(StreamType.OTHER, "audio/mpeg")).isEqualTo("audio/mpeg")
    }

    @Test
    fun progressiveStreamsWithoutUsableTypeLetExoPlayerSniff() {
        assertThat(PlayerMimeTypes.forStream(StreamType.MP4, null)).isNull()
        assertThat(PlayerMimeTypes.forStream(StreamType.MKV, "")).isNull()
        assertThat(PlayerMimeTypes.forStream(StreamType.OTHER, "video/*")).isNull()
        assertThat(PlayerMimeTypes.forStream(StreamType.TS, "application/octet-stream")).isNull()
        assertThat(PlayerMimeTypes.forStream(StreamType.MP4, "video/")).isNull()
    }

    @Test
    fun subtitleFormatsMapToMedia3Types() {
        assertThat(PlayerMimeTypes.forSubtitle(track("https://cdn.example/a.vtt", SubtitleFormat.VTT))).isEqualTo("text/vtt")
        assertThat(PlayerMimeTypes.forSubtitle(track("https://cdn.example/a.srt", SubtitleFormat.SRT)))
            .isEqualTo("application/x-subrip")
        assertThat(PlayerMimeTypes.forSubtitle(track("https://cdn.example/a.ttml", SubtitleFormat.TTML)))
            .isEqualTo("application/ttml+xml")
    }

    @Test
    fun unknownSubtitleFormatIsInferredFromUrlThenDefaultsToWebVtt() {
        assertThat(PlayerMimeTypes.forSubtitle(track("https://cdn.example/subs/en.srt?sig=1", SubtitleFormat.UNKNOWN)))
            .isEqualTo("application/x-subrip")
        assertThat(PlayerMimeTypes.forSubtitle(track("https://cdn.example/subs/en.dfxp", SubtitleFormat.UNKNOWN)))
            .isEqualTo("application/ttml+xml")
        assertThat(PlayerMimeTypes.forSubtitle(track("https://cdn.example/subtitles?id=42", SubtitleFormat.UNKNOWN)))
            .isEqualTo("text/vtt")
        assertThat(PlayerMimeTypes.forSubtitle(track("content://media/external/file/12", SubtitleFormat.UNKNOWN)))
            .isEqualTo("text/vtt")
    }

    @Test
    fun explicitSubtitleFormatWinsOverUrlExtension() {
        assertThat(PlayerMimeTypes.forSubtitle(track("https://cdn.example/converted.srt", SubtitleFormat.VTT)))
            .isEqualTo("text/vtt")
    }

    private fun track(url: String, format: SubtitleFormat) = SubtitleTrack(id = url.hashCode().toString(), url = url, format = format)
}
