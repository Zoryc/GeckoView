package app.snapcast.ui.local

import app.snapcast.core.media.M3uEntry
import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import java.net.Proxy
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class ChannelProbeTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    private val probe = ChannelProbe(client)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        server.close()
    }

    private fun channel(path: String, attributes: Map<String, String> = emptyMap()) =
        M3uEntry(name = "Channel", url = server.url(path).toString(), attributes = attributes)

    /** [packets] MPEG-TS packets: a 0x47 sync byte followed by filler. */
    private fun tsBytes(packets: Int, offset: Int = 0): ByteArray {
        val bytes = ByteArray(offset + packets * 188) { 0x11 }
        for (i in 0 until packets) bytes[offset + i * 188] = 0x47
        return bytes
    }

    @Test
    fun `an extension-less URL serving a playlist is HLS`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "text/plain")
                .body("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\nseg1.ts\n").build(),
        )

        val kind = probe.resolve(channel("/user/pass/12345", mapOf("http-user-agent" to "VLC/3.0")))

        assertThat(kind).isEqualTo(ChannelKind(StreamType.HLS, mimeType = null, live = true))
        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo("VLC/3.0")
    }

    @Test
    fun `raw MPEG-TS with a generic content type and no length is live TS`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/octet-stream")
                .chunkedBody(Buffer().write(tsBytes(40)), 512).build(),
        )

        val kind = probe.resolve(channel("/user/pass/777"))

        assertThat(kind).isEqualTo(ChannelKind(StreamType.TS, mimeType = "video/mp2t", live = true))
        assertThat(server.takeRequest().headers["Range"]).isNull()
    }

    @Test
    fun `http errors and unreachable servers leave the URL guess`() = runTest {
        server.enqueue(MockResponse(code = 404, body = "missing"))
        assertThat(probe.resolve(channel("/user/pass/404"))).isNull()

        val closed = MockWebServer().apply { start() }
        val url = closed.url("/live/1").toString()
        closed.close()
        assertThat(probe.resolve(M3uEntry(name = "Gone", url = url))).isNull()
    }

    @Test
    fun `URLs that name their format are not probed`() = runTest {
        assertThat(probe.resolve(channel("/live/news.m3u8"))).isNull()
        assertThat(probe.resolve(channel("/live/news.ts"))).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `results are remembered per URL`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("#EXTM3U\n").build())
        val channel = channel("/user/pass/5")

        val first = probe.resolve(channel)
        val second = probe.resolve(channel)

        assertThat(second).isEqualTo(first)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `the body wins over a wrong content type`() {
        val kind = ChannelProbe.classify(tsBytes(12), contentType = "application/vnd.apple.mpegurl", contentLength = null)
        assertThat(kind.type).isEqualTo(StreamType.TS)
        assertThat(kind.mimeType).isEqualTo("video/mp2t")
    }

    @Test
    fun `MPEG-TS is found when the body starts inside a packet`() {
        assertThat(ChannelProbe.classify(tsBytes(10, offset = 57), null, null).type).isEqualTo(StreamType.TS)
    }

    @Test
    fun `a single sync byte is not taken for MPEG-TS`() {
        val kind = ChannelProbe.classify("Gone".toByteArray(), contentType = "text/html", contentLength = 4)
        assertThat(kind).isEqualTo(ChannelKind(StreamType.OTHER, mimeType = null, live = false))
    }

    @Test
    fun `MP4 and DASH bodies and the content type fallback`() {
        val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + ByteArray(64)
        assertThat(ChannelProbe.classify(mp4, "video/mp4", 1_000_000)).isEqualTo(ChannelKind(StreamType.MP4, "video/mp4", live = false))
        val mpd = "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">".toByteArray()
        assertThat(ChannelProbe.classify(mpd, "text/xml", 300)).isEqualTo(ChannelKind(StreamType.DASH, null, live = false))
        assertThat(ChannelProbe.classify(ByteArray(0), "video/x-flv", null))
            .isEqualTo(ChannelKind(StreamType.OTHER, "video/x-flv", live = true))
        assertThat(ChannelProbe.classify(ByteArray(16), "application/x-mpegURL", null).type).isEqualTo(StreamType.HLS)
    }
}
