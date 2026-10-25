package app.snapcast.proxy.rewrite

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HlsRewriterTest {
    /** Readable stand-in for the signed routes. */
    private val mapper = object : ProxyUrlMapper {
        override fun exact(absoluteUrl: String): String = "U($absoluteUrl)"

        override fun base(baseDir: String): String = error("HLS uses exact routes only")
    }

    private fun rewrite(text: String, url: String = "https://cdn.example/live/master.m3u8") = HlsRewriter.rewrite(text, url, mapper)

    @Test
    fun `rewrites master playlist variants, renditions, i-frame streams and session tags`() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en/index.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Deutsch",LANGUAGE="de",URI="https://subs.example/de.m3u8"
            #EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="cc",NAME="CC1",INSTREAM-ID="CC1"
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720,AUDIO="aac",SUBTITLES="subs"
            720p/index.m3u8?token=abc
            #EXT-X-STREAM-INF:BANDWIDTH=640000,RESOLUTION=640x360
            /root/360p.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=86000,CODECS="avc1.4d401f",URI="iframes/720p.m3u8"
            #EXT-X-SESSION-DATA:DATA-ID="com.example.title",URI="meta/title.json"
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="../keys/session.key",IV=0x1234
        """.trimIndent() + "\n"

        val expected = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="U(https://cdn.example/live/audio/en/index.m3u8)"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Deutsch",LANGUAGE="de",URI="U(https://subs.example/de.m3u8)"
            #EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="cc",NAME="CC1",INSTREAM-ID="CC1"
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720,AUDIO="aac",SUBTITLES="subs"
            U(https://cdn.example/live/720p/index.m3u8?token=abc)
            #EXT-X-STREAM-INF:BANDWIDTH=640000,RESOLUTION=640x360
            U(https://cdn.example/root/360p.m3u8)
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=86000,CODECS="avc1.4d401f",URI="U(https://cdn.example/live/iframes/720p.m3u8)"
            #EXT-X-SESSION-DATA:DATA-ID="com.example.title",URI="U(https://cdn.example/live/meta/title.json)"
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="U(https://cdn.example/keys/session.key)",IV=0x1234
        """.trimIndent() + "\n"

        assertThat(rewrite(master)).isEqualTo(expected)
    }

    @Test
    fun `rewrites media playlist segments, keys, maps, parts and reports`() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k1?id=7&v=2",IV=0x00000000000000000000000000000001
            #EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00.000Z
            #EXTINF:6.000,Title with URI="not-a-uri"
            seg100.m4s
            #EXT-X-BYTERANGE:1000@0
            #EXTINF:6.000,
            https://other.example/seg101.m4s?sig=a%2Fb&x=1
            #EXT-X-PART:DURATION=1.0,URI="part102.0.m4s",INDEPENDENT=YES
            #EXT-X-PRELOAD-HINT:TYPE=PART,URI="part102.1.m4s"
            #EXT-X-RENDITION-REPORT:URI="../720p/index.m3u8",LAST-MSN=102
            ## a plain comment mentioning URI="x"
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"

        val expected = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-MAP:URI="U(https://cdn.example/live/360p/init.mp4)",BYTERANGE="720@0"
            #EXT-X-KEY:METHOD=AES-128,URI="U(https://keys.example/k1?id=7&v=2)",IV=0x00000000000000000000000000000001
            #EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00.000Z
            #EXTINF:6.000,Title with URI="not-a-uri"
            U(https://cdn.example/live/360p/seg100.m4s)
            #EXT-X-BYTERANGE:1000@0
            #EXTINF:6.000,
            U(https://other.example/seg101.m4s?sig=a%2Fb&x=1)
            #EXT-X-PART:DURATION=1.0,URI="U(https://cdn.example/live/360p/part102.0.m4s)",INDEPENDENT=YES
            #EXT-X-PRELOAD-HINT:TYPE=PART,URI="U(https://cdn.example/live/360p/part102.1.m4s)"
            #EXT-X-RENDITION-REPORT:URI="U(https://cdn.example/live/720p/index.m3u8)",LAST-MSN=102
            ## a plain comment mentioning URI="x"
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"

        assertThat(rewrite(media, "https://cdn.example/live/360p/index.m3u8")).isEqualTo(expected)
    }

    @Test
    fun `leaves skd, data and other non-http key uris untouched`() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://fairplay-key-id",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
            #EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="data:text/plain;base64,AAAAPnBzc2g=",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="urn:example:key"
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            ftp://files.example/seg.ts
        """.trimIndent()

        assertThat(rewrite(playlist)).isEqualTo(playlist)
    }

    @Test
    fun `resolves relative and absolute uris against the playlist url`() {
        val playlist = "#EXTM3U\n#EXTINF:4,\n../a/seg.ts\n#EXTINF:4,\n//other.example/b.ts\n#EXTINF:4,\n?part=3\n#EXTINF:4,\nhttp://plain.example/c.ts\n"
        val out = rewrite(playlist, "https://cdn.example/x/y/list.m3u8?auth=1")
        assertThat(out.lines()).containsAtLeast(
            "U(https://cdn.example/x/a/seg.ts)",
            "U(https://other.example/b.ts)",
            "U(https://cdn.example/x/y/list.m3u8?part=3)",
            "U(http://plain.example/c.ts)",
        ).inOrder()
    }

    @Test
    fun `preserves crlf line endings, blank lines, whitespace and a missing final newline`() {
        val playlist = "#EXTM3U\r\n#EXT-X-TARGETDURATION:4\r\n\r\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\r\n#EXTINF:4,\r\n  seg1.ts  \r\n#EXTINF:4,\nseg2.ts"
        val expected = "#EXTM3U\r\n#EXT-X-TARGETDURATION:4\r\n\r\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"U(https://cdn.example/live/k.bin)\"\r\n" +
            "#EXTINF:4,\r\n  U(https://cdn.example/live/seg1.ts)  \r\n#EXTINF:4,\nU(https://cdn.example/live/seg2.ts)"
        assertThat(rewrite(playlist)).isEqualTo(expected)
    }

    @Test
    fun `keeps a byte order mark and treats the first line as a tag`() {
        val playlist = "﻿#EXTM3U\n#EXTINF:4,\nseg.ts\n"
        assertThat(rewrite(playlist)).isEqualTo("﻿#EXTM3U\n#EXTINF:4,\nU(https://cdn.example/live/seg.ts)\n")
    }

    @Test
    fun `rewrites unquoted uri attributes and keeps attribute order`() {
        val playlist = "#EXTM3U\n#EXT-X-MAP:BYTERANGE=\"100@0\", URI=init.mp4\n"
        assertThat(rewrite(playlist)).isEqualTo("#EXTM3U\n#EXT-X-MAP:BYTERANGE=\"100@0\", URI=U(https://cdn.example/live/init.mp4)\n")
    }

    @Test
    fun `does not touch attributes that merely end with URI`() {
        val playlist = "#EXTM3U\n#EXT-X-CONTENT-STEERING:SERVER-URI=\"steering.json\",PATHWAY-ID=\"a\"\n" +
            "#EXT-X-DATERANGE:ID=\"ad\",START-DATE=\"2026-01-01T00:00:00Z\",X-ASSET-URI=\"ad.m3u8\"\n"
        assertThat(rewrite(playlist)).isEqualTo(playlist)
    }
}
