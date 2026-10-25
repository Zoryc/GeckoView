package app.snapcast.proxy

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.Closeable
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A running [StreamProxy] on 127.0.0.1 plus two upstream servers reachable as `origin.test` and `cdn.test`
 * (distinct host names for the cookie rules; both resolve to 127.0.0.1 through the upstream client's DNS).
 */
internal class ProxyTestHarness(
    config: ProxyConfig = ProxyConfig(port = 0, bindAddress = "127.0.0.1", defaultUserAgent = DEFAULT_UA),
) : Closeable {
    val origin = Upstream("origin.test")
    val cdn = Upstream("cdn.test")
    val logs = CopyOnWriteArrayList<String>()

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.endsWith(".test")) listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(hostname)
    }
    val upstreamClient: OkHttpClient = OkHttpClient.Builder().dns(dns).proxy(Proxy.NO_PROXY).build()
    val proxy = StreamProxy(upstreamClient, config, secret = "harness-secret".toByteArray(), log = { logs += it })

    /** Plays the receiver: no redirects, no retries, no system proxy. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    init {
        proxy.start()
    }

    fun proxyUrl(path: String): String = "http://127.0.0.1:${proxy.port}$path"

    /** Performs a request against the proxy and reads the whole answer. */
    fun fetch(path: String, headers: Map<String, String> = emptyMap(), method: String = "GET"): Fetched {
        val url = if (path.startsWith("http")) path else proxyUrl(path)
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(method, null)
        client.newCall(builder.build()).execute().use { response ->
            return Fetched(response.code, response.headers, response.body.bytes())
        }
    }

    override fun close() {
        proxy.stop()
        origin.close()
        cdn.close()
    }

    class Fetched(val code: Int, val headers: Headers, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)

        fun header(name: String): String? = headers[name]
    }

    /** A mock upstream host whose responses are chosen by path. */
    class Upstream(val host: String) : Closeable {
        private val server = MockWebServer()
        private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
        val requests = CopyOnWriteArrayList<RecordedRequest>()

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

        val port: Int get() = server.port

        fun url(path: String): String = "http://$host:${server.port}$path"

        fun on(path: String, handler: (RecordedRequest) -> MockResponse) {
            routes[path] = handler
        }

        fun text(path: String, body: String, contentType: String) = on(path) {
            MockResponse.Builder().code(200).setHeader("Content-Type", contentType).body(body).build()
        }

        /**
         * Serves [bytes] like a static file server: HEAD gets headers only; a Range request gets 206 when
         * [honourRange], the full body otherwise.
         */
        fun file(path: String, bytes: ByteArray, contentType: String, honourRange: Boolean = true) = on(path) { request ->
            val builder = MockResponse.Builder().setHeader("Content-Type", contentType)
            val range = request.headers["Range"]?.removePrefix("bytes=")
            when {
                request.method == "HEAD" -> builder.code(200).setHeader("Content-Length", bytes.size).setHeader("Accept-Ranges", "bytes")
                honourRange && range != null -> {
                    val start = range.substringBefore('-').toInt()
                    val end = range.substringAfter('-').toIntOrNull()?.coerceAtMost(bytes.size - 1) ?: (bytes.size - 1)
                    builder.code(206)
                        .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                        .body(Buffer().write(bytes, start, end - start + 1))
                }
                else -> builder.code(200).body(Buffer().write(bytes))
            }
            builder.build()
        }

        fun requestsTo(path: String): List<RecordedRequest> = requests.filter { it.url.encodedPath == path }

        override fun close() = server.close()
    }

    companion object {
        const val DEFAULT_UA = "SnapcastTest/1.0"

        fun bytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }
    }
}
