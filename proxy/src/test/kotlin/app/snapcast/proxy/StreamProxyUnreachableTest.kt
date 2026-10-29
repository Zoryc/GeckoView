package app.snapcast.proxy

import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.net.ServerSocket

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
        val problem = harness.proxy.takeRecentProblem(withinMs = 60_000)!!
        assertThat(problem.kind).isEqualTo(StreamProblem.Kind.UNREACHABLE)
        assertThat(problem.streamId).isEqualTo(stream.id)

        val missing = harness.proxy.registerStream(
            ProxyStreamRequest(harness.origin.url("/missing.m3u8"), emptyMap(), emptyMap(), StreamType.HLS),
        )
        harness.fetch(missing.path)
        assertThat(harness.proxy.takeRecentProblem(withinMs = 60_000)).isNull()
    }
}
