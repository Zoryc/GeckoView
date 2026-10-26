package app.snapcast.detection.inspect

import app.snapcast.detection.inspect.DirectMediaProbe.Media
import app.snapcast.detection.inspect.DirectMediaProbe.NotMedia
import app.snapcast.detection.inspect.DirectMediaProbe.Undecided
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DirectMediaProbeTest {
    private val html = "<!DOCTYPE html>\n<html lang=\"en\"><head><title>File:Big Buck Bunny 4K.webm</title>".toByteArray()
    private val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x86.toByte(), 0x81.toByte(), 0x01)
    private val mp4 = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()

    @Test
    fun `a video or adaptive streaming type confirms the document from HEAD alone`() {
        assertThat(DirectMediaProbe.fromHead(200, "video/webm")).isEqualTo(Media("video/webm"))
        assertThat(DirectMediaProbe.fromHead(200, "Video/MP4; codecs=\"avc1\"")).isEqualTo(Media("video/mp4"))
        assertThat(DirectMediaProbe.fromHead(200, "application/vnd.apple.mpegurl")).isEqualTo(Media("application/vnd.apple.mpegurl"))
        assertThat(DirectMediaProbe.fromHead(200, "application/x-mpegURL")).isEqualTo(Media("application/x-mpegurl"))
        assertThat(DirectMediaProbe.fromHead(200, "application/dash+xml")).isEqualTo(Media("application/dash+xml"))
    }

    @Test
    fun `an HTML page whose URL ends in a video extension is never media`() {
        assertThat(DirectMediaProbe.fromHead(200, "text/html; charset=UTF-8")).isEqualTo(NotMedia)
        assertThat(DirectMediaProbe.fromGet(200, "text/html; charset=UTF-8", html)).isEqualTo(NotMedia)
        // Even when the bytes would pass: the type is specific and not media.
        assertThat(DirectMediaProbe.fromGet(206, "text/html", webm)).isEqualTo(NotMedia)
        assertThat(DirectMediaProbe.fromHead(200, "application/xhtml+xml")).isEqualTo(NotMedia)
        assertThat(DirectMediaProbe.fromHead(200, "image/jpeg")).isEqualTo(NotMedia)
        assertThat(DirectMediaProbe.fromHead(200, "application/json")).isEqualTo(NotMedia)
        assertThat(DirectMediaProbe.fromHead(200, "audio/mpeg")).isEqualTo(NotMedia)
    }

    @Test
    fun `a failed HEAD or a generic type leaves the decision to the body`() {
        assertThat(DirectMediaProbe.fromHead(405, "text/html")).isEqualTo(Undecided)
        assertThat(DirectMediaProbe.fromHead(403, null)).isEqualTo(Undecided)
        assertThat(DirectMediaProbe.fromHead(200, null)).isEqualTo(Undecided)
        assertThat(DirectMediaProbe.fromHead(200, "application/octet-stream")).isEqualTo(Undecided)
        assertThat(DirectMediaProbe.fromHead(200, "text/plain")).isEqualTo(Undecided)
        assertThat(DirectMediaProbe.fromHead(200, "  ")).isEqualTo(Undecided)
    }

    @Test
    fun `a generic type is media only when the body is there and does not look like HTML`() {
        assertThat(DirectMediaProbe.fromGet(206, "application/octet-stream", webm)).isEqualTo(Media(null))
        assertThat(DirectMediaProbe.fromGet(200, null, mp4)).isEqualTo(Media(null))
        assertThat(DirectMediaProbe.fromGet(200, "text/plain", "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray())).isEqualTo(Media(null))
        assertThat(DirectMediaProbe.fromGet(206, "application/xml", "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">".toByteArray()))
            .isEqualTo(Media(null))
        assertThat(DirectMediaProbe.fromGet(200, "application/octet-stream", html)).isEqualTo(NotMedia)
        assertThat(DirectMediaProbe.fromGet(200, null, ByteArray(0))).isEqualTo(NotMedia)
    }

    @Test
    fun `a specific type from the ranged GET decides, and a failed GET is not media`() {
        assertThat(DirectMediaProbe.fromGet(206, "video/webm", webm)).isEqualTo(Media("video/webm"))
        assertThat(DirectMediaProbe.fromGet(200, "video/webm", ByteArray(0))).isEqualTo(Media("video/webm"))
        assertThat(DirectMediaProbe.fromGet(404, "video/webm", webm)).isEqualTo(NotMedia)
        assertThat(DirectMediaProbe.fromGet(302, null, ByteArray(0))).isEqualTo(NotMedia)
    }

    @Test
    fun `HTML is recognised after a byte order mark, white space, an XML declaration or comments`() {
        assertThat(DirectMediaProbe.looksLikeHtml(html)).isTrue()
        assertThat(DirectMediaProbe.looksLikeHtml(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<html>".toByteArray())).isTrue()
        assertThat(DirectMediaProbe.looksLikeHtml("\r\n  <HTML>".toByteArray())).isTrue()
        assertThat(DirectMediaProbe.looksLikeHtml("<!-- generated -->\n<head>".toByteArray())).isTrue()
        assertThat(DirectMediaProbe.looksLikeHtml("<?xml version=\"1.0\"?><!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0//EN\">".toByteArray())).isTrue()
        assertThat(DirectMediaProbe.looksLikeHtml("<?xml version=\"1.0\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\">".toByteArray())).isTrue()
        assertThat(DirectMediaProbe.looksLikeHtml("<p>Not found</p>".toByteArray())).isTrue()
        assertThat(DirectMediaProbe.looksLikeHtml("<!-- a comment longer than the sniffed bytes".toByteArray())).isTrue()
    }

    @Test
    fun `playlists, manifests and media signatures are not HTML`() {
        assertThat(DirectMediaProbe.looksLikeHtml("#EXTM3U\n".toByteArray())).isFalse()
        assertThat(DirectMediaProbe.looksLikeHtml("<?xml version=\"1.0\"?>\n<!-- GPAC -->\n<MPD>".toByteArray())).isFalse()
        assertThat(DirectMediaProbe.looksLikeHtml("<MPD type=\"static\">".toByteArray())).isFalse()
        assertThat(DirectMediaProbe.looksLikeHtml("<abbr>".toByteArray())).isFalse()
        assertThat(DirectMediaProbe.looksLikeHtml(webm)).isFalse()
        assertThat(DirectMediaProbe.looksLikeHtml(mp4)).isFalse()
        assertThat(DirectMediaProbe.looksLikeHtml(byteArrayOf(0x47, 0x40, 0x11, 0x10))).isFalse()
    }
}
