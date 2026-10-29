package app.snapcast.proxy

import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/** What the proxy serves is decided by the body: a playlist is rewritten only when it is one. */
class StreamProxyPlaylistBodyTest {
    private val harness = ProxyTestHarness()
    private val origin = harness.origin

    @After
    fun tearDown() = harness.close()

    private fun register(url: String, typeHint: StreamType? = StreamType.HLS) =
        harness.proxy.registerStream(ProxyStreamRequest(url, mapOf("Referer" to "https://page.example/"), emptyMap(), typeHint))

    /** The proxy paths in a rewritten playlist: URI lines and URI attributes. */
    private fun proxiedUris(playlist: String): List<String> = playlist.lines().flatMap { line ->
        when {
            line.startsWith("/") -> listOf(line)
            line.startsWith("#") -> Regex("URI=\"(/[^\"]+)\"").findAll(line).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }

    private fun bytes(body: ByteArray, contentType: String, encoding: String? = null) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", contentType)
        .apply { if (encoding != null) setHeader("Content-Encoding", encoding) }
        .body(Buffer().write(body))
        .build()

    @Test
    fun `an expired link's web page is an error for the receiver, not a playlist`() {
        origin.text("/x/master.m3u8", "<!DOCTYPE html><html><body>Lien expiré</body></html>", "text/html")
        val stream = register(origin.url("/x/master.m3u8"))

        val answer = harness.fetch(stream.path)

        assertThat(answer.code).isEqualTo(502)
        assertThat(answer.text).doesNotContain("/u/")
        val problem = harness.proxy.takeRecentProblem(withinMs = 60_000)!!
        assertThat(problem.kind).isEqualTo(StreamProblem.Kind.NOT_A_PLAYLIST)
        assertThat(problem.description).isEqualTo("a web page")
        assertThat(problem.streamId).isEqualTo(stream.id)
    }

    @Test
    fun `a playlist is rewritten whatever its type says, and a key labelled as a playlist stays a key`() {
        val key = ByteArray(16) { (it + 1).toByte() }
        origin.text("/v/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhls/720\n", "application/vnd.apple.mpegurl")
        // An /hls/ directory served as video/mp2t by the web server, and a key script with the playlist's type.
        origin.text("/v/hls/720", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.php\"\n#EXTINF:4,\nseg1.ts\n#EXT-X-ENDLIST\n", "video/mp2t")
        origin.on("/v/hls/key.php") { bytes(key, "application/vnd.apple.mpegurl") }
        val stream = register(origin.url("/v/master.m3u8"))

        val variant = harness.fetch(proxiedUris(harness.fetch(stream.path).text).single())
        val uris = proxiedUris(variant.text)
        assertThat(uris).hasSize(2)
        assertThat(harness.fetch(uris[0]).body).isEqualTo(key)
    }

    @Test
    fun `byte order marks and old Mac line breaks do not turn tags into URIs`() {
        origin.text("/b/master.m3u8", "﻿﻿#EXTM3U\r#EXT-X-STREAM-INF:BANDWIDTH=1\r﻿v.m3u8\r", "application/vnd.apple.mpegurl")
        val stream = register(origin.url("/b/master.m3u8"))

        val text = harness.fetch(stream.path).text

        assertThat(text).startsWith("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/")
        assertThat(proxiedUris(text)).hasSize(1)
    }

    @Test
    fun `a compressed playlist without a telling name is decoded and rewritten`() {
        val playlist = "#EXTM3U\n#EXTINF:4,\nseg1.ts\n#EXT-X-ENDLIST\n".toByteArray()
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(playlist) } }.toByteArray()
        val rawDeflate = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(playlist) }
        }.toByteArray()
        origin.text("/z/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\ngz/720\n#EXT-X-STREAM-INF:BANDWIDTH=2\ndf/720\n", "application/vnd.apple.mpegurl")
        // Compressed although the proxy asked for identity.
        origin.on("/z/gz/720") { bytes(gzipped, "text/plain", "gzip") }
        origin.on("/z/df/720") { bytes(rawDeflate, "text/plain", "deflate") }
        val stream = register(origin.url("/z/master.m3u8"))

        for (uri in proxiedUris(harness.fetch(stream.path).text)) {
            val variant = harness.fetch(uri)
            assertThat(variant.header("Content-Encoding")).isNull()
            assertThat(proxiedUris(variant.text)).hasSize(1)
        }
    }

    @Test
    fun `a bogus content encoding is ignored, an undecodable one is an error`() {
        origin.on("/e/master.m3u8") { bytes("#EXTM3U\n#EXTINF:4,\nseg1.ts\n".toByteArray(), "application/vnd.apple.mpegurl", "UTF-8") }
        origin.on("/e/brotli.m3u8") { bytes(byteArrayOf(0x1b, 0x2a, 0x00, 0x00), "application/vnd.apple.mpegurl", "br") }

        val text = harness.fetch(register(origin.url("/e/master.m3u8")).path)
        assertThat(text.code).isEqualTo(200)
        assertThat(proxiedUris(text.text)).hasSize(1)
        assertThat(harness.fetch(register(origin.url("/e/brotli.m3u8")).path).code).isEqualTo(502)
        assertThat(harness.proxy.takeRecentProblem(withinMs = 60_000)?.kind).isEqualTo(StreamProblem.Kind.UNSUPPORTED_ENCODING)
    }

    @Test
    fun `a media file typed as a stream is still served as media`() {
        val video = ProxyTestHarness.bytes(50_000)
        origin.file("/m/video", video, "application/octet-stream")
        val stream = register(origin.url("/m/video"))
        assertThat(harness.fetch(stream.path).body).isEqualTo(video)
        assertThat(harness.proxy.takeRecentProblem(withinMs = 60_000)).isNull()
    }
}
