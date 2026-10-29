package app.snapcast.proxy

import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Test
import java.net.Proxy
import java.net.ServerSocket
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** A server the phone cannot reach at all is reported, so the cast can go to it directly instead. */
class StreamProxyUnreachableTest {
    private val harness = ProxyTestHarness()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a refused connection is reported as unreachable, an HTTP error is not`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val stream = harness.proxy.registerStream(
            ProxyStreamRequest("http://127.0.0.1:$closedPort/v/master.m3u8", emptyMap(), emptyMap(), StreamType.HLS),
        )

        assertThat(harness.fetch(stream.path).code).isEqualTo(502)
        val problem = harness.proxy.takeRecentProblem(withinMs = 60_000, streamIds = listOf(stream.id))!!
        assertThat(problem.kind).isEqualTo(StreamProblem.Kind.UNREACHABLE)
        assertThat(problem.streamId).isEqualTo(stream.id)

        val missing = harness.proxy.registerStream(
            ProxyStreamRequest(harness.origin.url("/missing.m3u8"), emptyMap(), emptyMap(), StreamType.HLS),
        )
        harness.fetch(missing.path)
        assertThat(harness.proxy.takeRecentProblem(withinMs = 60_000)).isNull()
    }

    @Test
    fun `a problem explains only its own stream's failure, once`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        fun register(name: String) = harness.proxy.registerStream(
            ProxyStreamRequest("http://127.0.0.1:$closedPort/$name.m3u8", emptyMap(), emptyMap(), StreamType.HLS),
        )
        val first = register("a")
        val second = register("b")
        harness.fetch(first.path)
        harness.fetch(second.path)

        assertThat(harness.proxy.takeRecentProblem(60_000, listOf("other"))).isNull()
        assertThat(harness.proxy.takeRecentProblem(60_000, listOf(first.id))?.streamId).isEqualTo(first.id)
        assertThat(harness.proxy.takeRecentProblem(60_000, listOf(first.id))).isNull()
        assertThat(harness.proxy.takeRecentProblem(60_000, listOf(first.id, second.id))?.streamId).isEqualTo(second.id)
    }

    @Test
    fun `among TLS failures only an untrusted certificate counts as unreachable`() {
        val upstream = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .addInterceptor { chain ->
                when (chain.request().url.encodedPath) {
                    "/untrusted.m3u8" -> throw SSLHandshakeException("handshake failed").apply {
                        initCause(CertificateException("Trust anchor for certification path not found."))
                    }
                    else -> throw SSLException("Read error: I/O error during system call, Connection reset by peer")
                }
            }
            .build()
        val proxy = StreamProxy(upstream, ProxyConfig(port = 0, bindAddress = "127.0.0.1"), secret = "s".toByteArray())
        proxy.start()
        try {
            fun fetch(path: String) = harness.client.newCall(Request("http://127.0.0.1:${proxy.port}$path".toHttpUrl())).execute().use { it.code }
            val untrusted = proxy.registerStream(ProxyStreamRequest("https://cdn.test/untrusted.m3u8", emptyMap(), emptyMap(), StreamType.HLS))
            val reset = proxy.registerStream(ProxyStreamRequest("https://cdn.test/reset.m3u8", emptyMap(), emptyMap(), StreamType.HLS))

            assertThat(fetch(reset.path)).isEqualTo(502)
            assertThat(proxy.takeRecentProblem(60_000)).isNull()
            assertThat(fetch(untrusted.path)).isEqualTo(502)
            assertThat(proxy.takeRecentProblem(60_000)?.kind).isEqualTo(StreamProblem.Kind.UNREACHABLE)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `the stream id is read back from a proxy URL`() {
        val stream = harness.proxy.registerStream(ProxyStreamRequest(harness.origin.url("/v.m3u8"), emptyMap(), emptyMap(), StreamType.HLS))
        assertThat(StreamProxy.streamIdOf("http://192.168.1.20:8642${stream.path}")).isEqualTo(stream.id)
        assertThat(StreamProxy.streamIdOf("https://cdn.example/stream.m3u8")).isNull()
        assertThat(StreamProxy.streamIdOf("http://192.168.1.20:8642/stream/")).isNull()
    }
}
