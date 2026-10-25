package app.snapcast.proxy.http

import app.snapcast.proxy.server.ProxyRoutes
import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PathCodecTest {

    @Test
    fun `encodes characters that are not allowed in a path`() {
        assertThat(PathCodec.encodePath("video 1/seg#2?.ts")).isEqualTo("video%201/seg%232%3F.ts")
        assertThat(PathCodec.encodePath("100%/ü.m4s")).isEqualTo("100%25/%C3%BC.m4s")
        assertThat(PathCodec.encodePath("emoji-🎬.ts")).isEqualTo("emoji-%F0%9F%8E%AC.ts")
    }

    @Test
    fun `keeps path-safe characters`() {
        val safe = "a/b-c_d.e~f!g\$h&i'j(k)l*m+n,o;p=q:r@s"
        assertThat(PathCodec.encodePath(safe)).isEqualTo(safe)
    }

    @Test
    fun `protects plus signs in the path only`() {
        assertThat(PathCodec.protectPlusInPath("a+b/seg+\$Number\$.m4s?q=x+y")).isEqualTo("a%2Bb/seg%2B\$Number\$.m4s?q=x+y")
        assertThat(PathCodec.protectPlusInPath("plain.m4s")).isEqualTo("plain.m4s")
    }

    @Test
    fun `base64url round trip`() {
        val url = "https://cdn.example/path?a=1&b=ü"
        val encoded = Base64Url.encode(url)
        assertThat(encoded).doesNotContain("=")
        assertThat(encoded).doesNotContain("/")
        assertThat(encoded).doesNotContain("+")
        assertThat(Base64Url.decodeToString(encoded)).isEqualTo(url)
        assertThat(Base64Url.decodeToString("not base64!")).isNull()
    }

    @Test
    fun `stream names follow the stream type`() {
        assertThat(ProxyRoutes.streamName("https://x.example/live/playlist.m3u8", StreamType.HLS)).isEqualTo("index.m3u8")
        assertThat(ProxyRoutes.streamName("https://x.example/a/stream.mpd", StreamType.DASH)).isEqualTo("manifest.mpd")
        assertThat(ProxyRoutes.streamName("https://x.example/My%20Movie.mp4?sig=1", StreamType.MP4)).isEqualTo("My_Movie.mp4")
        assertThat(ProxyRoutes.streamName("https://x.example/", StreamType.WEBM)).isEqualTo("video.webm")
        assertThat(ProxyRoutes.streamName("https://x.example/%E2%9C%93", null)).isEqualTo("video.mp4")
    }

    @Test
    fun `file hints are sanitized`() {
        assertThat(ProxyRoutes.fileHint("https://x.example/a/seg-1.ts?x=1")).isEqualTo("seg-1.ts")
        assertThat(ProxyRoutes.fileHint("https://x.example/a/")).isEqualTo("r")
        assertThat(ProxyRoutes.fileHint("https://x.example/a/..")).isEqualTo("r")
        assertThat(ProxyRoutes.fileHint("https://x.example/" + "a".repeat(100) + ".ts")).hasLength(80)
    }
}
