package app.snapcast.proxy

import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/** EXT-X-DEFINE references are substituted before the proxy signs URIs into routes, as players do. */
class StreamProxyVariablesTest {
    private val harness = ProxyTestHarness()
    private val origin = harness.origin

    @After
    fun tearDown() = harness.close()

    private fun proxiedUris(playlist: String): List<String> = playlist.lines().flatMap { line ->
        when {
            line.startsWith("/") -> listOf(line)
            line.startsWith("#") -> Regex("URI=\"(/[^\"]+)\"").findAll(line).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }

    @Test
    fun `a token passed by QUERYPARAM and a host imported from the master reach the CDN`() {
        val type = "application/vnd.apple.mpegurl"
        origin.text(
            "/d/master.m3u8",
            "#EXTM3U\n#EXT-X-DEFINE:QUERYPARAM=\"tok\"\n#EXT-X-DEFINE:NAME=\"dir\",VALUE=\"hd\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1\n{\$dir}/v.m3u8?tok={\$tok}\n",
            type,
        )
        origin.text(
            "/d/hd/v.m3u8",
            "#EXTM3U\n#EXT-X-DEFINE:IMPORT=\"dir\"\n#EXT-X-DEFINE:QUERYPARAM=\"tok\"\n" +
                "#EXT-X-MAP:URI=\"init.mp4?tok={\$tok}\"\n#EXTINF:4,\nseg-{\$dir}-1.m4s?tok={\$tok}\n#EXT-X-ENDLIST\n",
            type,
        )
        origin.text("/d/hd/init.mp4", "init", "video/mp4")
        origin.text("/d/hd/seg-hd-1.m4s", "segment", "video/iso.segment")
        val stream = harness.proxy.registerStream(
            ProxyStreamRequest(origin.url("/d/master.m3u8?tok=abc"), emptyMap(), emptyMap(), StreamType.HLS),
        )

        val master = harness.fetch(stream.path).text
        assertThat(master).contains("#EXT-X-DEFINE:NAME=\"tok\",VALUE=\"abc\"")
        val media = harness.fetch(proxiedUris(master).single()).text
        assertThat(media).contains("#EXT-X-DEFINE:NAME=\"dir\",VALUE=\"hd\"")
        val (init, segment) = proxiedUris(media)

        assertThat(harness.fetch(init).text).isEqualTo("init")
        assertThat(harness.fetch(segment).text).isEqualTo("segment")
        assertThat(origin.requestsTo("/d/hd/v.m3u8").single().url.queryParameter("tok")).isEqualTo("abc")
        assertThat(origin.requestsTo("/d/hd/seg-hd-1.m4s").single().url.queryParameter("tok")).isEqualTo("abc")
    }
}
