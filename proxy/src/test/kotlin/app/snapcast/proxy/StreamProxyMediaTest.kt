package app.snapcast.proxy

import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.assertFailsWith

/** Progressive media through the proxy: header forwarding, Range, HEAD, errors. */
class StreamProxyMediaTest {
    private val harness = ProxyTestHarness()
    private val origin = harness.origin
    private val video = ProxyTestHarness.bytes(5000)

    @After
    fun tearDown() = harness.close()

    private fun register(
        path: String = "/media/movie.mp4",
        headers: Map<String, String> = CAPTURED,
        typeHint: StreamType? = StreamType.MP4,
    ): ProxiedStream = harness.proxy.registerStream(ProxyStreamRequest(origin.url(path), headers, typeHint = typeHint))

    @Test
    fun `GET streams the body and replays the captured headers`() {
        origin.file("/media/movie.mp4", video, "video/mp4")
        val stream = register()
        assertThat(stream.path).isEqualTo("/stream/${stream.id}/movie.mp4")

        val response = harness.fetch(
            stream.path,
            mapOf(
                "User-Agent" to "Receiver/2.0",
                "Origin" to "https://receiver.example",
                "Cookie" to "receiver=1",
                "If-None-Match" to "\"etag\"",
                "If-Modified-Since" to "Sat, 01 Jan 2000 00:00:00 GMT",
            ),
        )

        assertThat(response.code).isEqualTo(200)
        assertThat(response.body).isEqualTo(video)
        assertThat(response.header("Content-Type")).isEqualTo("video/mp4")
        assertThat(response.header("Content-Length")).isEqualTo("5000")
        assertThat(response.header("Accept-Ranges")).isEqualTo("bytes")
        val upstream = origin.requestsTo("/media/movie.mp4").single().headers
        assertThat(upstream["Referer"]).isEqualTo("https://page.example/watch")
        assertThat(upstream["User-Agent"]).isEqualTo("BrowserUA/1.0")
        assertThat(upstream["Origin"]).isEqualTo("https://page.example")
        assertThat(upstream["X-Requested-With"]).isEqualTo("snapcast")
        assertThat(upstream["Cookie"]).isEqualTo("session=abc")
        assertThat(upstream["If-None-Match"]).isNull()
        assertThat(upstream["If-Modified-Since"]).isNull()
        assertThat(upstream["Host"]).isEqualTo("origin.test:${origin.port}")
    }

    @Test
    fun `media is requested with Accept-Encoding identity`() {
        origin.file("/media/movie.mp4", video, "video/mp4")
        val stream = register()

        harness.fetch(stream.path, mapOf("Accept-Encoding" to "gzip, deflate"))

        assertThat(origin.requestsTo("/media/movie.mp4").single().headers["Accept-Encoding"]).isEqualTo("identity")
    }

    @Test
    fun `default user agent is used when none was captured`() {
        origin.file("/media/movie.mp4", video, "video/mp4")
        val stream = register(headers = mapOf("Referer" to "https://page.example/"))

        harness.fetch(stream.path, mapOf("User-Agent" to "Receiver/2.0"))

        assertThat(origin.requestsTo("/media/movie.mp4").single().headers["User-Agent"]).isEqualTo(ProxyTestHarness.DEFAULT_UA)
    }

    @Test
    fun `Range is forwarded and a 206 passes through`() {
        origin.file("/media/movie.mp4", video, "video/mp4")
        val stream = register()

        val response = harness.fetch(stream.path, mapOf("Range" to "bytes=100-199"))

        assertThat(response.code).isEqualTo(206)
        assertThat(response.header("Content-Range")).isEqualTo("bytes 100-199/5000")
        assertThat(response.header("Content-Length")).isEqualTo("100")
        assertThat(response.header("Accept-Ranges")).isEqualTo("bytes")
        assertThat(response.body).isEqualTo(video.copyOfRange(100, 200))
        assertThat(origin.requestsTo("/media/movie.mp4").single().headers["Range"]).isEqualTo("bytes=100-199")
    }

    @Test
    fun `multi-range requests forward only the first range`() {
        origin.file("/media/movie.mp4", video, "video/mp4")
        val stream = register()

        val response = harness.fetch(stream.path, mapOf("Range" to "bytes=0-9, 20-29"))

        assertThat(response.code).isEqualTo(206)
        assertThat(response.body).isEqualTo(video.copyOfRange(0, 10))
        assertThat(origin.requestsTo("/media/movie.mp4").single().headers["Range"]).isEqualTo("bytes=0-9")
    }

    @Test
    fun `Range is emulated when the upstream ignores it`() {
        origin.file("/media/movie.mp4", video, "video/mp4", honourRange = false)
        val stream = register()

        val open = harness.fetch(stream.path, mapOf("Range" to "bytes=4000-"))
        assertThat(open.code).isEqualTo(206)
        assertThat(open.header("Content-Range")).isEqualTo("bytes 4000-4999/5000")
        assertThat(open.header("Content-Length")).isEqualTo("1000")
        assertThat(open.body).isEqualTo(video.copyOfRange(4000, 5000))

        val suffix = harness.fetch(stream.path, mapOf("Range" to "bytes=-10"))
        assertThat(suffix.code).isEqualTo(206)
        assertThat(suffix.header("Content-Range")).isEqualTo("bytes 4990-4999/5000")
        assertThat(suffix.body).isEqualTo(video.copyOfRange(4990, 5000))

        val bounded = harness.fetch(stream.path, mapOf("Range" to "bytes=10-19"))
        assertThat(bounded.code).isEqualTo(206)
        assertThat(bounded.body).isEqualTo(video.copyOfRange(10, 20))
    }

    @Test
    fun `unsatisfiable range answers 416 with the total length`() {
        origin.file("/media/movie.mp4", video, "video/mp4", honourRange = false)
        val stream = register()

        val response = harness.fetch(stream.path, mapOf("Range" to "bytes=9000-"))

        assertThat(response.code).isEqualTo(416)
        assertThat(response.header("Content-Range")).isEqualTo("bytes */5000")
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo("*")
    }

    @Test
    fun `upstream 416 passes through with its Content-Range`() {
        origin.on("/media/movie.mp4") {
            MockResponse.Builder().code(416).setHeader("Content-Range", "bytes */5000").build()
        }
        val stream = register()

        val response = harness.fetch(stream.path, mapOf("Range" to "bytes=9000-"))

        assertThat(response.code).isEqualTo(416)
        assertThat(response.header("Content-Range")).isEqualTo("bytes */5000")
    }

    @Test
    fun `unknown upstream length is streamed chunked with plain 200 even for a Range request`() {
        origin.on("/media/live.ts") {
            MockResponse.Builder().code(200).setHeader("Content-Type", "video/mp2t").chunkedBody(Buffer().write(video), 700).build()
        }
        val stream = register("/media/live.ts", typeHint = StreamType.TS)

        val response = harness.fetch(stream.path, mapOf("Range" to "bytes=100-"))

        assertThat(response.code).isEqualTo(200)
        assertThat(response.header("Content-Length")).isNull()
        assertThat(response.body).isEqualTo(video)
    }

    @Test
    fun `generic content type of the main resource is replaced by the stream type`() {
        origin.file("/media/movie.mp4", video, "application/octet-stream")
        val stream = register()

        assertThat(harness.fetch(stream.path).header("Content-Type")).isEqualTo("video/mp4")
        assertThat(harness.fetch(stream.path, method = "HEAD").header("Content-Type")).isEqualTo("video/mp4")
    }

    @Test
    fun `HEAD uses the upstream HEAD`() {
        origin.file("/media/movie.mp4", video, "video/mp4")
        val stream = register()

        val response = harness.fetch(stream.path, method = "HEAD")

        assertThat(response.code).isEqualTo(200)
        assertThat(response.header("Content-Length")).isEqualTo("5000")
        assertThat(response.header("Accept-Ranges")).isEqualTo("bytes")
        assertThat(response.header("Content-Type")).isEqualTo("video/mp4")
        assertThat(response.body).isEmpty()
        assertThat(origin.requestsTo("/media/movie.mp4").map { it.method }).containsExactly("HEAD")
    }

    @Test
    fun `HEAD falls back to a ranged GET when the upstream refuses HEAD`() {
        origin.on("/media/movie.mp4") { request ->
            if (request.method == "HEAD") {
                MockResponse.Builder().code(405).build()
            } else {
                MockResponse.Builder().code(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes 0-0/123456789")
                    .body(Buffer().writeByte(0))
                    .build()
            }
        }
        val stream = register()

        val response = harness.fetch(stream.path, method = "HEAD")

        assertThat(response.code).isEqualTo(200)
        assertThat(response.header("Content-Length")).isEqualTo("123456789")
        assertThat(response.header("Accept-Ranges")).isEqualTo("bytes")
        val requests = origin.requestsTo("/media/movie.mp4")
        assertThat(requests.map { it.method }).containsExactly("HEAD", "GET").inOrder()
        assertThat(requests[1].headers["Range"]).isEqualTo("bytes=0-0")
    }

    @Test
    fun `HEAD without a length asks for the first byte to learn it`() {
        origin.on("/media/movie.mp4") { request ->
            if (request.method == "HEAD") {
                MockResponse.Builder().code(200).removeHeader("Content-Length").setHeader("Content-Type", "video/mp4").build()
            } else {
                MockResponse.Builder().code(206)
                    .setHeader("Content-Range", "bytes 0-0/777")
                    .body(Buffer().writeByte(0))
                    .build()
            }
        }
        val stream = register()

        val response = harness.fetch(stream.path, method = "HEAD")

        assertThat(response.code).isEqualTo(200)
        assertThat(response.header("Content-Length")).isEqualTo("777")
        assertThat(response.header("Content-Type")).isEqualTo("video/mp4")
        assertThat(origin.requestsTo("/media/movie.mp4").map { it.method }).containsExactly("HEAD", "GET").inOrder()
    }

    @Test
    fun `HEAD with a Range announces the partial length`() {
        origin.file("/media/movie.mp4", video, "video/mp4")
        val stream = register()

        val response = harness.fetch(stream.path, mapOf("Range" to "bytes=1000-1999"), method = "HEAD")

        assertThat(response.code).isEqualTo(206)
        assertThat(response.header("Content-Length")).isEqualTo("1000")
        assertThat(response.header("Content-Range")).isEqualTo("bytes 1000-1999/5000")
    }

    @Test
    fun `HEAD answers carry the length but no body on the wire`() {
        origin.file("/media/movie.mp4", video, "video/mp4")
        val stream = register()

        val raw = Socket("127.0.0.1", harness.proxy.port).use { socket ->
            socket.getOutputStream().write("HEAD ${stream.path} HTTP/1.1\r\nHost: test\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

        assertThat(raw).startsWith("HTTP/1.1 200 OK\r\n")
        assertThat(raw).contains("\r\nContent-Length: 5000\r\n")
        assertThat(raw).doesNotContain("Transfer-Encoding")
        assertThat(raw).endsWith("\r\n\r\n")
        assertThat(raw.indexOf("\r\n\r\n")).isEqualTo(raw.length - 4)
    }

    @Test
    fun `upstream error statuses pass through`() {
        origin.on("/media/movie.mp4") { MockResponse(code = 410, body = "gone") }
        val stream = register()

        val response = harness.fetch(stream.path)

        assertThat(response.code).isEqualTo(410)
        assertThat(response.text).isEqualTo("gone")
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo("*")
    }

    @Test
    fun `redirects are followed upstream and never exposed`() {
        origin.on("/media/movie.mp4") {
            MockResponse.Builder().code(302).setHeader("Location", "/files/real.mp4").build()
        }
        origin.file("/files/real.mp4", video, "video/mp4")
        val stream = register()

        val response = harness.fetch(stream.path)

        assertThat(response.code).isEqualTo(200)
        assertThat(response.header("Location")).isNull()
        assertThat(response.body).isEqualTo(video)
        assertThat(origin.requestsTo("/files/real.mp4").single().headers["Referer"]).isEqualTo("https://page.example/watch")
    }

    @Test
    fun `upstream connection failure answers 502`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val stream = harness.proxy.registerStream(
            ProxyStreamRequest("http://127.0.0.1:$closedPort/movie.mp4", CAPTURED, typeHint = StreamType.MP4),
        )

        val response = harness.fetch(stream.path)

        assertThat(response.code).isEqualTo(502)
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo("*")
        assertThat(harness.logs.joinToString()).contains("Upstream failure")
    }

    @Test
    fun `a truncated upstream body closes the client connection instead of hanging`() {
        // Cut after the first bytes the proxy looks at (a cut within them is answered 502 before anything is sent).
        origin.on("/media/movie.mp4") {
            MockResponse.Builder().code(200)
                .setHeader("Content-Type", "video/mp4")
                .body(Buffer().write(ByteArray(10_000)))
                .setHeader("Content-Length", 20_000)
                .onResponseEnd(SocketEffect.ShutdownConnection)
                .build()
        }
        val stream = register()

        assertFailsWith<IOException> { harness.fetch(stream.path) }
        // The proxy keeps serving.
        assertThat(harness.fetch("/health").code).isEqualTo(200)
    }

    @Test
    fun `large bodies are streamed intact`() {
        val big = ProxyTestHarness.bytes(3 * 1024 * 1024 + 17)
        origin.file("/media/big.mp4", big, "video/mp4")
        val stream = register("/media/big.mp4")

        val response = harness.fetch(stream.path)

        assertThat(response.code).isEqualTo(200)
        assertThat(response.body.size).isEqualTo(big.size)
        assertThat(response.body).isEqualTo(big)
    }

    @Test
    fun `keep-alive connections serve several requests in a row`() {
        origin.file("/media/movie.mp4", video, "video/mp4")
        val stream = register()

        repeat(5) { index ->
            val start = index * 100
            val response = harness.fetch(stream.path, mapOf("Range" to "bytes=$start-${start + 99}"))
            assertThat(response.code).isEqualTo(206)
            assertThat(response.body).isEqualTo(video.copyOfRange(start, start + 100))
            harness.fetch(stream.path, method = "HEAD")
        }
    }

    private companion object {
        val CAPTURED = mapOf(
            "Referer" to "https://page.example/watch",
            "User-Agent" to "BrowserUA/1.0",
            "Origin" to "https://page.example",
            "Cookie" to "session=abc",
            "X-Requested-With" to "snapcast",
            "Range" to "bytes=0-",
            "Accept-Encoding" to "gzip",
            "Host" to "page.example",
        )
    }
}
