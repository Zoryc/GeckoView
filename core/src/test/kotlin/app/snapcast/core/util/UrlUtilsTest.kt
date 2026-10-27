package app.snapcast.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlUtilsTest {
    private val rfcBase = "http://a/b/c/d;p?q"

    private fun assertResolves(reference: String, expected: String, base: String = rfcBase) {
        assertThat(UrlUtils.resolve(base, reference)).isEqualTo(expected)
    }

    @Test
    fun `resolve RFC 3986 normal examples`() {
        assertResolves("g:h", "g:h")
        assertResolves("g", "http://a/b/c/g")
        assertResolves("./g", "http://a/b/c/g")
        assertResolves("g/", "http://a/b/c/g/")
        assertResolves("/g", "http://a/g")
        assertResolves("//g", "http://g")
        assertResolves("?y", "http://a/b/c/d;p?y")
        assertResolves("g?y", "http://a/b/c/g?y")
        assertResolves("#s", "http://a/b/c/d;p?q#s")
        assertResolves("g#s", "http://a/b/c/g#s")
        assertResolves("g?y#s", "http://a/b/c/g?y#s")
        assertResolves(";x", "http://a/b/c/;x")
        assertResolves("g;x", "http://a/b/c/g;x")
        assertResolves("g;x?y#s", "http://a/b/c/g;x?y#s")
        assertResolves("", "http://a/b/c/d;p?q")
        assertResolves(".", "http://a/b/c/")
        assertResolves("./", "http://a/b/c/")
        assertResolves("..", "http://a/b/")
        assertResolves("../", "http://a/b/")
        assertResolves("../g", "http://a/b/g")
        assertResolves("../..", "http://a/")
        assertResolves("../../", "http://a/")
        assertResolves("../../g", "http://a/g")
    }

    @Test
    fun `resolve RFC 3986 abnormal examples`() {
        assertResolves("../../../g", "http://a/g")
        assertResolves("../../../../g", "http://a/g")
        assertResolves("/./g", "http://a/g")
        assertResolves("/../g", "http://a/g")
        assertResolves("g.", "http://a/b/c/g.")
        assertResolves(".g", "http://a/b/c/.g")
        assertResolves("g..", "http://a/b/c/g..")
        assertResolves("..g", "http://a/b/c/..g")
        assertResolves("./../g", "http://a/b/g")
        assertResolves("./g/.", "http://a/b/c/g/")
        assertResolves("g/./h", "http://a/b/c/g/h")
        assertResolves("g/../h", "http://a/b/c/h")
        assertResolves("g;x=1/./y", "http://a/b/c/g;x=1/y")
        assertResolves("g;x=1/../y", "http://a/b/c/y")
        assertResolves("g?y/./x", "http://a/b/c/g?y/./x")
        assertResolves("g?y/../x", "http://a/b/c/g?y/../x")
        assertResolves("g#s/./x", "http://a/b/c/g#s/./x")
        assertResolves("g#s/../x", "http://a/b/c/g#s/../x")
        assertResolves("http:g", "http:g")
    }

    @Test
    fun `resolve real-world playlist references`() {
        val master = "https://cdn.example.com/hls/movie/master.m3u8?token=abc"
        assertResolves("720p/index.m3u8", "https://cdn.example.com/hls/movie/720p/index.m3u8", master)
        assertResolves("../keys/k1.bin", "https://cdn.example.com/hls/keys/k1.bin", master)
        assertResolves("/root.m3u8", "https://cdn.example.com/root.m3u8", master)
        assertResolves("//other.cdn.net/a.ts", "https://other.cdn.net/a.ts", master)
        assertResolves("https://abs.example.org/x.ts", "https://abs.example.org/x.ts", master)
        assertResolves("?token=new", "https://cdn.example.com/hls/movie/master.m3u8?token=new", master)
        assertResolves("#frag", "https://cdn.example.com/hls/movie/master.m3u8?token=abc#frag", master)
    }

    @Test
    fun `resolve keeps characters java net URI rejects`() {
        val base = "https://example.com/videos/My Show/master.m3u8"
        assertResolves("seg 1|a.ts", "https://example.com/videos/My Show/seg 1|a.ts", base)
        assertResolves("chunk.ts?a=b|c&d=e f", "https://example.com/videos/My Show/chunk.ts?a=b|c&d=e f", base)
    }

    @Test
    fun `resolve trims whitespace and leaves opaque references alone`() {
        assertResolves("  seg.ts \r", "http://a/b/c/seg.ts")
        assertResolves("data:text/plain,../x", "data:text/plain,../x")
        assertResolves("skd://key-id-123", "skd://key-id-123")
        assertResolves("HTTPS://Example.com/a/../b", "HTTPS://Example.com/b")
    }

    @Test
    fun `resolve against base with empty path`() {
        assertResolves("seg.ts", "https://example.com/seg.ts", "https://example.com")
    }

    @Test
    fun `hostOf extracts lower-cased host`() {
        assertThat(UrlUtils.hostOf("https://User:pw@Media.Example.COM:8443/path?q#f")).isEqualTo("media.example.com")
        assertThat(UrlUtils.hostOf("http://[::1]:8080/x")).isEqualTo("[::1]")
        assertThat(UrlUtils.hostOf("HTTP://example.org")).isEqualTo("example.org")
        assertThat(UrlUtils.hostOf("ftp://example.org/file")).isNull()
        assertThat(UrlUtils.hostOf("blob:https://example.org/uuid")).isNull()
        assertThat(UrlUtils.hostOf("not a url")).isNull()
        assertThat(UrlUtils.hostOf("http:///nohost")).isNull()
    }

    @Test
    fun `hostMatches handles subdomains case and www`() {
        assertThat(UrlUtils.hostMatches("ads.doubleclick.net", "doubleclick.net")).isTrue()
        assertThat(UrlUtils.hostMatches("DoubleClick.NET", "doubleclick.net")).isTrue()
        assertThat(UrlUtils.hostMatches("www.example.com", "example.com")).isTrue()
        assertThat(UrlUtils.hostMatches("example.com", "www.example.com")).isTrue()
        assertThat(UrlUtils.hostMatches("notdoubleclick.net", "doubleclick.net")).isFalse()
        assertThat(UrlUtils.hostMatches("doubleclick.net.evil.com", "doubleclick.net")).isFalse()
        assertThat(UrlUtils.hostMatches("example.com", "")).isFalse()
    }

    @Test
    fun `normalizeForDedupe drops fragment volatile params and default ports`() {
        assertThat(UrlUtils.normalizeForDedupe("HTTPS://CDN.Example.com:443/Path/Video.mp4?b=2&range=0-100&a=1#t=10"))
            .isEqualTo("https://cdn.example.com/Path/Video.mp4?b=2&a=1")
        assertThat(UrlUtils.normalizeForDedupe("http://example.com:80/v.mp4?_=1700000000&bytestart=0&byteend=99"))
            .isEqualTo("http://example.com/v.mp4")
        assertThat(UrlUtils.normalizeForDedupe("https://r1.googlevideo.com/videoplayback?id=x&rn=3&rbuf=0&itag=18"))
            .isEqualTo("https://r1.googlevideo.com/videoplayback?id=x&itag=18")
        assertThat(UrlUtils.normalizeForDedupe("http://example.com:8080/v.mp4"))
            .isEqualTo("http://example.com:8080/v.mp4")
    }

    @Test
    fun `normalizeForDedupe drops only numeric cache busters`() {
        assertThat(UrlUtils.normalizeForDedupe("https://e.com/v.m3u8?t=1700000000&ts=12&cb=5&x=1"))
            .isEqualTo("https://e.com/v.m3u8?x=1")
        assertThat(UrlUtils.normalizeForDedupe("https://e.com/v.m3u8?t=abc&cb=x1"))
            .isEqualTo("https://e.com/v.m3u8?t=abc&cb=x1")
    }

    @Test
    fun `normalizeForDedupe drops LL-HLS delivery directives`() {
        assertThat(UrlUtils.normalizeForDedupe("https://e.com/720p.m3u8?_HLS_msn=12&_HLS_part=2&_HLS_skip=YES&x=1"))
            .isEqualTo("https://e.com/720p.m3u8?x=1")
        assertThat(UrlUtils.normalizeForDedupe("https://e.com/720p.m3u8?_hls_msn=1&_HLS_Future=v2"))
            .isEqualTo("https://e.com/720p.m3u8")
        assertThat(UrlUtils.stableId("https://e.com/720p.m3u8?_HLS_msn=1234&_HLS_part=2"))
            .isEqualTo(UrlUtils.stableId("https://e.com/720p.m3u8"))
        // Only the reserved prefix is dropped.
        assertThat(UrlUtils.normalizeForDedupe("https://e.com/v.m3u8?hls_msn=1&x_HLS_part=2"))
            .isEqualTo("https://e.com/v.m3u8?hls_msn=1&x_HLS_part=2")
    }

    @Test
    fun `withoutHlsDirectives removes only the directives and keeps the rest byte for byte`() {
        assertThat(UrlUtils.withoutHlsDirectives("https://e.com/720p.m3u8?_HLS_msn=1234&_HLS_part=2"))
            .isEqualTo("https://e.com/720p.m3u8")
        assertThat(UrlUtils.withoutHlsDirectives("HTTPS://E.com:443/a/720p.m3u8?tok=A%2Fb&_HLS_msn=5&_=9&_hls_skip=v2#f"))
            .isEqualTo("HTTPS://E.com:443/a/720p.m3u8?tok=A%2Fb&_=9#f")
        assertThat(UrlUtils.withoutHlsDirectives("https://e.com/v.m3u8?_HLS_part=1#frag")).isEqualTo("https://e.com/v.m3u8#frag")
        assertThat(UrlUtils.withoutHlsDirectives("https://e.com/v.m3u8?_HLS_msn=1&")).isEqualTo("https://e.com/v.m3u8")
        val untouched = listOf(
            "https://e.com/v.m3u8",
            "https://e.com/v.m3u8?",
            "https://e.com/v.m3u8?a=1&&b=2",
            "https://e.com/v.m3u8?x=1#_HLS_msn=2",
            "https://e.com/v.m3u8?hls_msn=1",
            "blob:https://e.com/uuid",
        )
        for (url in untouched) assertThat(UrlUtils.withoutHlsDirectives(url)).isEqualTo(url)
    }

    @Test
    fun `normalizeForDedupe treats empty path as root`() {
        assertThat(UrlUtils.normalizeForDedupe("https://Example.com")).isEqualTo("https://example.com/")
        assertThat(UrlUtils.normalizeForDedupe("https://example.com/?")).isEqualTo("https://example.com/")
    }

    @Test
    fun `stableId is 16 hex chars and equal for equivalent URLs`() {
        val id = UrlUtils.stableId("https://example.com/video.mp4")
        assertThat(id).hasLength(16)
        assertThat(id).matches("[0-9a-f]{16}")
        assertThat(UrlUtils.stableId("HTTPS://EXAMPLE.com:443/video.mp4#x")).isEqualTo(id)
        assertThat(UrlUtils.stableId("https://example.com/video.mp4?range=0-1")).isEqualTo(id)
        assertThat(UrlUtils.stableId("https://example.com/other.mp4")).isNotEqualTo(id)
    }

    @Test
    fun `stableId is the SHA-256 prefix of the normalized URL`() {
        val normalized = UrlUtils.normalizeForDedupe("https://example.com/a.mp4?_=1")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        val expected = digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
        assertThat(UrlUtils.stableId("https://example.com/a.mp4?_=1")).isEqualTo(expected)
    }

    @Test
    fun `extensionOf ignores query fragment and case`() {
        assertThat(UrlUtils.extensionOf("https://e.com/path/Master.M3U8?x=1.mp4#y.ts")).isEqualTo("m3u8")
        assertThat(UrlUtils.extensionOf("https://e.com/v.mp4;jsessionid=abc")).isEqualTo("mp4")
        assertThat(UrlUtils.extensionOf("https://e.com/dir.v2/file")).isNull()
        assertThat(UrlUtils.extensionOf("https://e.com/")).isNull()
        assertThat(UrlUtils.extensionOf("https://e.com")).isNull()
        assertThat(UrlUtils.extensionOf("https://e.com/file.")).isNull()
        assertThat(UrlUtils.extensionOf("relative/file.webm")).isEqualTo("webm")
        assertThat(UrlUtils.extensionOf("data:video/mp4;base64,AAAA.mp4")).isNull()
    }

    @Test
    fun `fileNameOf decodes the last segment`() {
        assertThat(UrlUtils.fileNameOf("https://e.com/videos/My%20Movie%2B1.mp4?x=1")).isEqualTo("My Movie+1.mp4")
        assertThat(UrlUtils.fileNameOf("https://e.com/a/caf%C3%A9.vtt")).isEqualTo("café.vtt")
        assertThat(UrlUtils.fileNameOf("https://e.com/a/100%.mp4")).isEqualTo("100%.mp4")
        assertThat(UrlUtils.fileNameOf("https://e.com/dir/")).isNull()
        assertThat(UrlUtils.fileNameOf("https://e.com")).isNull()
        assertThat(UrlUtils.fileNameOf("about:blank")).isNull()
    }

    @Test
    fun `isHttp accepts only http and https`() {
        assertThat(UrlUtils.isHttp("HTTPS://e.com")).isTrue()
        assertThat(UrlUtils.isHttp("http://e.com")).isTrue()
        assertThat(UrlUtils.isHttp("blob:https://e.com/x")).isFalse()
        assertThat(UrlUtils.isHttp("data:video/mp4,x")).isFalse()
    }

    @Test
    fun `referrer follows the browser's default policy`() {
        val page = "https://Site.example.com/watch/1?ep=2#comments"
        assertThat(UrlUtils.referrerFor(page, "https://site.example.com/og.jpg")).isEqualTo("https://site.example.com/watch/1?ep=2")
        assertThat(UrlUtils.referrerFor(page, "https://img.example.org/og.jpg")).isEqualTo("https://site.example.com/")
        assertThat(UrlUtils.referrerFor(page, "http://site.example.com/og.jpg")).isNull()
        assertThat(UrlUtils.referrerFor("http://a.example:8080/p", "http://a.example:8080/x")).isEqualTo("http://a.example:8080/p")
        assertThat(UrlUtils.referrerFor("http://a.example:8080/p", "http://b.example:8080/x")).isEqualTo("http://a.example:8080/")
        assertThat(UrlUtils.referrerFor("https://user:pw@a.example/p", "https://a.example/x")).isEqualTo("https://a.example/p")
        assertThat(UrlUtils.referrerFor("https://a.example:443", "https://a.example/x")).isEqualTo("https://a.example/")
        assertThat(UrlUtils.referrerFor("http://[::1]:8000/p", "http://cdn.example/x")).isEqualTo("http://[::1]:8000/")
        assertThat(UrlUtils.referrerFor("about:blank", "https://a.example/x")).isNull()
    }

    @Test
    fun `cookie headers merge by name with the later value winning`() {
        assertThat(CookieHeaders.merge("a=1; b=2", null, "b=3;c=4", "")).isEqualTo("a=1; b=3; c=4")
        assertThat(CookieHeaders.merge("token=x=y; flag")).isEqualTo("token=x=y; flag")
        assertThat(CookieHeaders.merge(null, " ")).isNull()
        // Within one header the first value of a name is the one servers read; a later header still replaces it.
        assertThat(CookieHeaders.merge("sid=specific; sid=general; x=1")).isEqualTo("sid=specific; x=1")
        assertThat(CookieHeaders.merge("sid=a; sid=b", "sid=new")).isEqualTo("sid=new")
    }

    @Test
    fun `bounded cookies keep the stream's host, then the newest hosts that fit`() {
        val cookies = linkedMapOf("old.ads" to "x".repeat(50), "cdn.example" to "a=1", "new.ads" to "y".repeat(20), "seg.example" to "s=2")
        val kept = CookieHeaders.bounded(cookies, listOf("CDN.example"), maxChars = 60)
        assertThat(kept.keys).containsExactly("cdn.example", "seg.example", "new.ads").inOrder()
        assertThat(CookieHeaders.bounded(cookies, emptyList(), maxChars = 1_000)).hasSize(4)
    }
}
