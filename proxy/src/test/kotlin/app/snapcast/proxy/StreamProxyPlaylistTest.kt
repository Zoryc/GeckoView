package app.snapcast.proxy

import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** HLS and DASH end to end: rewritten playlists are followed like a receiver would. */
class StreamProxyPlaylistTest {
    private val harness = ProxyTestHarness()
    private val origin = harness.origin
    private val cdn = harness.cdn

    @After
    fun tearDown() = harness.close()

    private fun register(
        url: String,
        typeHint: StreamType? = StreamType.HLS,
        headers: Map<String, String> = mapOf("Referer" to "https://page.example/"),
        cookiesByHost: Map<String, String> = emptyMap(),
    ) = harness.proxy.registerStream(ProxyStreamRequest(url, headers, cookiesByHost, typeHint))

    /** The proxy paths in a rewritten playlist: URI lines and URI attributes. */
    private fun proxiedUris(playlist: String): List<String> = playlist.lines().flatMap { line ->
        when {
            line.startsWith("/") -> listOf(line)
            line.startsWith("#") -> Regex("URI=\"(/[^\"]+)\"").findAll(line).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }

    @Test
    fun `HLS end to end - master, variant, key and segments`() {
        val segment1 = ProxyTestHarness.bytes(1500)
        val segment2 = ProxyTestHarness.bytes(900)
        val key = ByteArray(16) { it.toByte() }
        origin.text(
            "/hls/master.m3u8",
            "#EXTM3U\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",URI=\"audio/en.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO=\"a\"\n" +
                "video/720p.m3u8\n",
            "application/vnd.apple.mpegurl",
        )
        origin.text(
            "/hls/video/720p.m3u8",
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"../keys/k1.bin\"\n" +
                "#EXTINF:4,\nseg1.ts\n" +
                "#EXTINF:4,\n${cdn.url("/hls/seg2.ts")}\n" +
                "#EXT-X-ENDLIST\n",
            "application/x-mpegURL",
        )
        origin.file("/hls/keys/k1.bin", key, "application/octet-stream")
        origin.file("/hls/video/seg1.ts", segment1, "video/mp2t")
        cdn.file("/hls/seg2.ts", segment2, "video/mp2t")
        val stream = register(origin.url("/hls/master.m3u8"))
        assertThat(stream.path).isEqualTo("/stream/${stream.id}/index.m3u8")

        val master = harness.fetch(stream.path)
        assertThat(master.code).isEqualTo(200)
        assertThat(master.header("Content-Type")).isEqualTo("application/vnd.apple.mpegurl")
        assertThat(master.header("Cache-Control")).isEqualTo("no-cache")
        assertThat(master.header("Content-Length")).isEqualTo(master.body.size.toString())
        assertThat(master.text).doesNotContain("origin.test")
        val (audioPath, variantPath) = proxiedUris(master.text)
        assertThat(audioPath).endsWith("/en.m3u8")
        assertThat(variantPath).startsWith("/u/${stream.id}/")
        assertThat(variantPath).endsWith("/720p.m3u8")

        val variant = harness.fetch(variantPath)
        assertThat(variant.code).isEqualTo(200)
        assertThat(variant.header("Content-Type")).isEqualTo("application/vnd.apple.mpegurl")
        val (keyPath, segment1Path, segment2Path) = proxiedUris(variant.text)
        assertThat(variant.text).contains("#EXT-X-KEY:METHOD=AES-128,URI=\"$keyPath\"")

        assertThat(harness.fetch(keyPath).body).isEqualTo(key)
        val first = harness.fetch(segment1Path)
        assertThat(first.body).isEqualTo(segment1)
        assertThat(first.header("Content-Type")).isEqualTo("video/mp2t")
        assertThat(harness.fetch(segment2Path).body).isEqualTo(segment2)
        val ranged = harness.fetch(segment1Path, mapOf("Range" to "bytes=0-99"))
        assertThat(ranged.code).isEqualTo(206)
        assertThat(ranged.body).isEqualTo(segment1.copyOfRange(0, 100))

        assertThat(origin.requestsTo("/hls/video/seg1.ts").first().headers["Referer"]).isEqualTo("https://page.example/")
        assertThat(cdn.requestsTo("/hls/seg2.ts").single().headers["Referer"]).isEqualTo("https://page.example/")
        assertThat(origin.requestsTo("/hls/keys/k1.bin")).hasSize(1)
    }

    @Test
    fun `playlists keep relative urls working after a redirect`() {
        origin.on("/r/start.m3u8") {
            MockResponse.Builder().code(302).setHeader("Location", cdn.url("/real/dir/master.m3u8")).build()
        }
        cdn.text("/real/dir/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n", "application/vnd.apple.mpegurl")
        cdn.text("/real/dir/v.m3u8", "#EXTM3U\n#EXTINF:4,\ns.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
        val stream = register(origin.url("/r/start.m3u8"), headers = mapOf("Cookie" to "origin=1"))

        val master = harness.fetch(stream.path)
        val variant = harness.fetch(proxiedUris(master.text).single())

        assertThat(variant.code).isEqualTo(200)
        assertThat(cdn.requestsTo("/real/dir/v.m3u8")).hasSize(1)
        assertThat(Regex("/u/[^\\s]+/s\\.ts").containsMatchIn(variant.text)).isTrue()
        // The origin's cookie stays on the origin, even across the redirect.
        assertThat(origin.requestsTo("/r/start.m3u8").single().headers["Cookie"]).isEqualTo("origin=1")
        assertThat(cdn.requests.map { it.headers["Cookie"] }).containsExactly(null, null)
    }

    @Test
    fun `cookies are only sent to the stream host and hosts with their own cookies`() {
        origin.text(
            "/c/list.m3u8",
            "#EXTM3U\n#EXTINF:4,\nlocal.ts\n#EXTINF:4,\n${cdn.url("/c/remote.ts")}\n#EXT-X-ENDLIST\n",
            "application/vnd.apple.mpegurl",
        )
        origin.text("/c/local.ts", "local", "video/mp2t")
        cdn.text("/c/remote.ts", "remote", "video/mp2t")

        val withCdnCookie = register(
            origin.url("/c/list.m3u8"),
            headers = mapOf("Cookie" to "origin=1"),
            cookiesByHost = mapOf("CDN.test" to "cdn=2"),
        )
        proxiedUris(harness.fetch(withCdnCookie.path).text).forEach { harness.fetch(it) }
        assertThat(origin.requestsTo("/c/local.ts").single().headers["Cookie"]).isEqualTo("origin=1")
        assertThat(cdn.requestsTo("/c/remote.ts").single().headers["Cookie"]).isEqualTo("cdn=2")

        val withoutCdnCookie = register(origin.url("/c/list.m3u8"), headers = mapOf("Cookie" to "origin=1"))
        proxiedUris(harness.fetch(withoutCdnCookie.path).text).forEach { harness.fetch(it) }
        assertThat(cdn.requestsTo("/c/remote.ts").last().headers["Cookie"]).isNull()
        assertThat(origin.requestsTo("/c/local.ts").last().headers["Cookie"]).isEqualTo("origin=1")
    }

    @Test
    fun `playlists are recognised by sniffing when nothing else tells`() {
        origin.text("/api/get", "#EXTM3U\n#EXTINF:4,\nchunk-1\n#EXT-X-ENDLIST\n", "application/octet-stream")
        val stream = register(origin.url("/api/get?id=5"), typeHint = null)

        val response = harness.fetch(stream.path)

        assertThat(response.header("Content-Type")).isEqualTo("application/vnd.apple.mpegurl")
        assertThat(proxiedUris(response.text)).hasSize(1)
        assertThat(origin.requestsTo("/api/get").single().url.query).isEqualTo("id=5")
    }

    @Test
    fun `binary bodies with a generic type pass through untouched`() {
        val binary = ByteArray(10_000) { if (it % 7 == 0) 0 else (it % 200 + 1).toByte() }
        origin.file("/api/blob", binary, "application/octet-stream")
        val stream = register(origin.url("/api/blob"), typeHint = null)

        val response = harness.fetch(stream.path)

        assertThat(response.code).isEqualTo(200)
        assertThat(response.header("Content-Length")).isEqualTo("10000")
        assertThat(response.body).isEqualTo(binary)
    }

    @Test
    fun `a stream hinted as HLS that turns out to be media is streamed as is`() {
        val media = ProxyTestHarness.bytes(3000)
        origin.file("/wrong/video.m3u8", media, "video/mp4")
        val stream = register(origin.url("/wrong/video.m3u8"))

        val response = harness.fetch(stream.path)

        assertThat(response.body).isEqualTo(media)
        assertThat(response.header("Content-Type")).isEqualTo("video/mp4")
    }

    @Test
    fun `HEAD on a playlist announces the rewritten length`() {
        origin.text("/h/list.m3u8", "#EXTM3U\n#EXTINF:4,\nseg.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
        val stream = register(origin.url("/h/list.m3u8"))

        val get = harness.fetch(stream.path)
        val head = harness.fetch(stream.path, method = "HEAD")

        assertThat(head.code).isEqualTo(200)
        assertThat(head.body).isEmpty()
        assertThat(head.header("Content-Length")).isEqualTo(get.body.size.toString())
        assertThat(head.header("Content-Type")).isEqualTo("application/vnd.apple.mpegurl")
    }

    @Test
    fun `gzip encoded playlists are decoded before rewriting`() {
        val playlist = "#EXTM3U\n#EXTINF:4,\nseg.ts\n#EXT-X-ENDLIST\n"
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(playlist.toByteArray()) } }.toByteArray()
        // No extension: requested with Accept-Encoding identity, but the server compresses anyway.
        origin.on("/gz/list") {
            MockResponse.Builder().code(200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setHeader("Content-Encoding", "gzip")
                .body(Buffer().write(gzipped))
                .build()
        }
        val stream = register(origin.url("/gz/list"), typeHint = null)

        val response = harness.fetch(stream.path)

        assertThat(response.header("Content-Encoding")).isNull()
        assertThat(response.text).startsWith("#EXTM3U\n#EXTINF:4,\n/u/${stream.id}/")
    }

    @Test
    fun `playlist range requests are answered with the whole playlist`() {
        origin.text("/p/list.m3u8", "#EXTM3U\n#EXTINF:4,\nseg.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
        val stream = register(origin.url("/p/list.m3u8"))

        val response = harness.fetch(stream.path, mapOf("Range" to "bytes=0-5"))

        assertThat(response.code).isEqualTo(200)
        assertThat(response.text).startsWith("#EXTM3U")
        assertThat(origin.requestsTo("/p/list.m3u8").single().headers["Range"]).isNull()
    }

    @Test
    fun `DASH end to end - templates, init segment and BaseURL file`() {
        val init = ProxyTestHarness.bytes(200)
        val segment = ProxyTestHarness.bytes(4000)
        val audio = ProxyTestHarness.bytes(2500)
        origin.text(
            "/dash/manifest.mpd",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT8S" minBufferTime="PT2S">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <SegmentTemplate media="${'$'}RepresentationID${'$'}/seg-${'$'}Number${'$'}.m4s" initialization="${'$'}RepresentationID${'$'}/init.mp4" startNumber="1" duration="4" timescale="1"/>
                  <Representation id="v720" bandwidth="1000000" width="1280" height="720"/>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="a" bandwidth="128000">
                    <BaseURL>${cdn.url("/audio/track.mp4")}</BaseURL>
                    <SegmentBase indexRange="0-99"/>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
            """.trimIndent(),
            "application/dash+xml",
        )
        origin.file("/dash/v720/init.mp4", init, "video/mp4")
        origin.file("/dash/v720/seg-2.m4s", segment, "video/iso.segment")
        cdn.file("/audio/track.mp4", audio, "audio/mp4")
        val stream = register(origin.url("/dash/manifest.mpd"), typeHint = StreamType.DASH)
        assertThat(stream.path).endsWith("/manifest.mpd")

        val manifest = harness.fetch(stream.path)
        assertThat(manifest.code).isEqualTo(200)
        assertThat(manifest.header("Content-Type")).isEqualTo("application/dash+xml")
        assertThat(manifest.header("Cache-Control")).isEqualTo("no-cache")
        assertThat(manifest.text).doesNotContain("origin.test")
        assertThat(manifest.text).doesNotContain("cdn.test")

        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(manifest.text)))
        val template = document.getElementsByTagNameNS("*", "SegmentTemplate").item(0) as Element
        val media = template.getAttribute("media").replace("\$RepresentationID\$", "v720").replace("\$Number\$", "2")
        val initialization = template.getAttribute("initialization").replace("\$RepresentationID\$", "v720")
        assertThat(media).startsWith("/b/${stream.id}/")

        assertThat(harness.fetch(media).body).isEqualTo(segment)
        assertThat(harness.fetch(initialization).body).isEqualTo(init)
        val audioPath = document.getElementsByTagNameNS("*", "BaseURL").item(0).textContent
        val audioRange = harness.fetch(audioPath, mapOf("Range" to "bytes=0-99"))
        assertThat(audioRange.code).isEqualTo(206)
        assertThat(audioRange.body).isEqualTo(audio.copyOfRange(0, 100))
        assertThat(origin.requestsTo("/dash/v720/seg-2.m4s").single().headers["Referer"]).isEqualTo("https://page.example/")
    }

    @Test
    fun `DASH Location refreshes are proxied and rewritten again`() {
        val manifest = { location: String ->
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"dynamic\"><Location>$location</Location>" +
                "<Period><AdaptationSet><SegmentTemplate media=\"s-${'$'}Number${'$'}.m4s\"/>" +
                "<Representation id=\"v\" bandwidth=\"1\"/></AdaptationSet></Period></MPD>"
        }
        origin.text("/live/start.mpd", manifest("next/refresh.mpd"), "application/dash+xml")
        // An old DASH MIME type that MediaClassifier does not know: the URL decides.
        origin.text("/live/next/refresh.mpd", manifest("refresh"), "video/vnd.mpeg.dash.mpd")
        // Without a telling URL either, the body is sniffed.
        origin.text("/live/next/refresh", manifest("refresh.mpd"), "video/vnd.mpeg.dash.mpd")
        val stream = register(origin.url("/live/start.mpd"), typeHint = StreamType.DASH)

        val first = harness.fetch(stream.path).text
        val location = Regex("<Location>([^<]+)</Location>").find(first)!!.groupValues[1]
        assertThat(location).startsWith("/u/${stream.id}/")

        val refreshed = harness.fetch(location)
        assertThat(refreshed.code).isEqualTo(200)
        assertThat(refreshed.header("Content-Type")).isEqualTo("application/dash+xml")
        assertThat(refreshed.text).contains("media=\"/b/${stream.id}/")
        assertThat(refreshed.text).doesNotContain("origin.test")

        val sniffed = harness.fetch(Regex("<Location>([^<]+)</Location>").find(refreshed.text)!!.groupValues[1])
        assertThat(sniffed.header("Content-Type")).isEqualTo("application/dash+xml")
        assertThat(sniffed.text).doesNotContain("origin.test")
    }

    @Test
    fun `DASH manifests with a DTD are refused`() {
        origin.text(
            "/dash/evil.mpd",
            "<?xml version=\"1.0\"?><!DOCTYPE MPD [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><MPD>&e;</MPD>",
            "application/dash+xml",
        )
        val stream = register(origin.url("/dash/evil.mpd"), typeHint = StreamType.DASH)

        val response = harness.fetch(stream.path)

        assertThat(response.code).isEqualTo(502)
        assertThat(response.text).doesNotContain("root:")
    }
}
