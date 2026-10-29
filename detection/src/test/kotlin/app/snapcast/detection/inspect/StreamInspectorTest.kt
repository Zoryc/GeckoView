package app.snapcast.detection.inspect

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Test

class StreamInspectorTest {
    private val origin = Host("video.site.test")
    private val cdn = Host("cdn.thirdparty.test")

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.endsWith(".test")) listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(hostname)
    }

    /** Like the app's shared client: it follows redirects itself, which the inspector must not let it do. */
    private val shared = OkHttpClient.Builder()
        .dns(dns)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val inspector = StreamInspector(shared)

    @After
    fun tearDown() {
        shared.dispatcher.cancelAll()
        shared.connectionPool.evictAll()
        origin.close()
        cdn.close()
    }

    @Test
    fun `a DASH manifest served as HLS is inspected as DASH and says so`() {
        val mpd = """<?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT1H2M3S">
              <Period><AdaptationSet mimeType="video/mp4"><Representation id="v" bandwidth="1000000" width="1280" height="720"/></AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        origin.text("/stream/abc/manifest", mpd, HLS_MIME)

        val result = inspector.inspect(video(origin.url("/stream/abc/manifest"), StreamType.HLS))

        assertThat(result!!.type).isEqualTo(StreamType.DASH)
        assertThat(result.durationSec).isEqualTo(3723.0)
    }

    @Test
    fun `a variant redirected to another host gets neither the site's cookie nor its Authorization`() {
        origin.text("/hls/master.m3u8", MASTER, HLS_MIME)
        origin.redirect("/hls/720.m3u8", cdn.url("/signed/720.m3u8"))
        cdn.text("/signed/720.m3u8", MEDIA, HLS_MIME)
        val video = video(origin.url("/hls/master.m3u8"), StreamType.HLS)

        val result = inspector.inspect(video)

        assertThat(result).isNotNull()
        assertThat(result!!.variants.map { it.height }).containsExactly(360, 720)
        // The media playlist was read through the redirect.
        assertThat(result.durationSec).isEqualTo(20.0)
        for (path in listOf("/hls/master.m3u8", "/hls/720.m3u8")) {
            val request = origin.requestsTo(path).single()
            assertThat(request.headers["Cookie"]).isEqualTo("session=secret")
            assertThat(request.headers["Authorization"]).isEqualTo("Bearer token")
        }
        val atCdn = cdn.requestsTo("/signed/720.m3u8").single()
        assertThat(atCdn.headers["Cookie"]).isNull()
        assertThat(atCdn.headers["Authorization"]).isNull()
        assertThat(atCdn.headers["Referer"]).isEqualTo("http://video.site.test/watch")
    }

    @Test
    fun `every hop gets its own host's cookie, and Authorization never comes back after a host change`() {
        origin.redirect("/movie.mp4", cdn.url("/hop"))
        cdn.redirect("/hop", origin.url("/final.mp4"))
        origin.on("/final.mp4") { request ->
            MockResponse.Builder().code(200)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Length", 1_234_567)
                .apply { if (request.method != "HEAD") body("x") }
                .build()
        }
        val video = video(origin.url("/movie.mp4"), StreamType.MP4, cdnCookie = "cdn=1")

        val result = inspector.inspect(video)

        assertThat(result?.sizeBytes).isEqualTo(1_234_567L)
        val first = origin.requestsTo("/movie.mp4").single()
        assertThat(first.method).isEqualTo("HEAD")
        assertThat(first.headers["Authorization"]).isEqualTo("Bearer token")
        val hop = cdn.requestsTo("/hop").single()
        assertThat(hop.method).isEqualTo("HEAD")
        assertThat(hop.headers["Cookie"]).isEqualTo("cdn=1")
        assertThat(hop.headers["Authorization"]).isNull()
        val last = origin.requestsTo("/final.mp4").single()
        assertThat(last.method).isEqualTo("HEAD")
        assertThat(last.headers["Cookie"]).isEqualTo("session=secret")
        assertThat(last.headers["Authorization"]).isNull()
    }

    @Test
    fun `a redirect loop ends the inspection without a result`() {
        origin.redirect("/loop.m3u8", origin.url("/loop.m3u8"))
        assertThat(inspector.inspect(video(origin.url("/loop.m3u8"), StreamType.HLS))).isNull()
        assertThat(origin.requestsTo("/loop.m3u8")).hasSize(11)
    }

    @Test
    fun `an HTML page whose URL ends in webm is not confirmed as direct media`() {
        val page = "<!DOCTYPE html><html><head><title>File:Big Buck Bunny 4K.webm</title></head></html>"
        origin.on("/wiki/File:Big_Buck_Bunny_4K.webm") { request ->
            MockResponse.Builder().code(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .apply { if (request.method != "HEAD") body(page) }
                .build()
        }
        val document = DetectedVideo(id = "d", tabId = "t", url = origin.url("/wiki/File:Big_Buck_Bunny_4K.webm"), type = StreamType.WEBM)

        assertThat(inspector.confirmDirectMedia(document)).isNull()
        assertThat(origin.requestsTo("/wiki/File:Big_Buck_Bunny_4K.webm").map { it.method }).containsExactly("HEAD")
    }

    @Test
    fun `a real file is confirmed, by HEAD or by sniffing a ranged GET`() {
        origin.on("/movie.webm") { MockResponse.Builder().code(200).setHeader("Content-Type", "video/webm").build() }
        val webm = DetectedVideo(id = "w", tabId = "t", url = origin.url("/movie.webm"), type = StreamType.WEBM)
        assertThat(inspector.confirmDirectMedia(webm)).isEqualTo(DirectMediaProbe.Media("video/webm"))

        // HEAD refused, generic type: the first bytes decide.
        val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(2048)
        origin.on("/download.webm") { request ->
            if (request.method == "HEAD") {
                MockResponse(code = 405)
            } else {
                MockResponse.Builder().code(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .body(Buffer().write(ebml))
                    .build()
            }
        }
        val download = DetectedVideo(id = "x", tabId = "t", url = origin.url("/download.webm"), type = StreamType.WEBM)
        assertThat(inspector.confirmDirectMedia(download)).isEqualTo(DirectMediaProbe.Media(null))
        assertThat(origin.requestsTo("/download.webm").last().headers["Range"]).isEqualTo("bytes=0-511")

        // Generic type, HTML body.
        origin.on("/fake.mp4") { request ->
            MockResponse.Builder().code(200)
                .setHeader("Content-Type", "application/octet-stream")
                .apply { if (request.method != "HEAD") body("<html><body>Sign in</body></html>") }
                .build()
        }
        val fake = DetectedVideo(id = "f", tabId = "t", url = origin.url("/fake.mp4"), type = StreamType.MP4)
        assertThat(inspector.confirmDirectMedia(fake)).isNull()
    }

    private fun video(url: String, type: StreamType, cdnCookie: String? = null) = DetectedVideo(
        id = "v",
        tabId = "t",
        url = url,
        type = type,
        headers = MediaHeaders(
            referer = "http://video.site.test/watch",
            userAgent = "Gecko UA",
            cookie = "session=secret",
            extra = mapOf("Authorization" to "Bearer token"),
        ),
        cookiesByHost = buildMap {
            put("video.site.test", "session=secret")
            if (cdnCookie != null) put("cdn.thirdparty.test", cdnCookie)
        },
    )

    /** A mock host (resolved to 127.0.0.1 by [dns]) whose responses are chosen by path. */
    private class Host(val name: String) : Closeable {
        private val server = MockWebServer()
        private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
        private val requests = CopyOnWriteArrayList<RecordedRequest>()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val handler = routes[request.url.encodedPath] ?: return MockResponse(code = 404, body = "missing")
                    return handler(request)
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        fun url(path: String): String = "http://$name:${server.port}$path"

        fun on(path: String, handler: (RecordedRequest) -> MockResponse) {
            routes[path] = handler
        }

        fun text(path: String, body: String, contentType: String) = on(path) {
            MockResponse.Builder().code(200).setHeader("Content-Type", contentType).body(body).build()
        }

        fun redirect(path: String, location: String) = on(path) {
            MockResponse.Builder().code(302).setHeader("Location", location).build()
        }

        fun requestsTo(path: String): List<RecordedRequest> = requests.filter { it.url.encodedPath == path }

        override fun close() = server.close()
    }

    private companion object {
        const val HLS_MIME = "application/vnd.apple.mpegurl"
        val MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            720.m3u8
        """.trimIndent()
        val MEDIA = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
    }
}
