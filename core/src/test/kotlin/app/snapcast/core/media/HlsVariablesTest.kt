package app.snapcast.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertIs

class HlsVariablesTest {
    private val masterUrl = "https://cdn.example/hls/master.m3u8?token=a%2Fb&lang=fr"

    @Test
    fun `NAME, QUERYPARAM and IMPORT definitions give values, the first one of a name winning`() {
        val text = """
            #EXTM3U
            #EXT-X-DEFINE:NAME="base",VALUE="hd"
            #EXT-X-DEFINE:NAME="base",VALUE="sd"
            #EXT-X-DEFINE:QUERYPARAM="token"
            #EXT-X-DEFINE:QUERYPARAM="missing"
            #EXT-X-DEFINE:IMPORT="cdn"
            #EXT-X-DEFINE:NAME="bad name",VALUE="x"
        """.trimIndent()

        val variables = HlsVariables.defined(text, masterUrl, imported = mapOf("cdn" to "edge-2"))

        assertThat(variables).containsExactly("base", "hd", "token", "a/b", "cdn", "edge-2").inOrder()
    }

    @Test
    fun `references are substituted in URIs and attributes, unknown ones stay as written`() {
        val text = "#EXTM3U\n" +
            "#EXT-X-DEFINE:NAME=\"v\",VALUE=\"hi\"\r\n" +
            "#EXT-X-DEFINE:QUERYPARAM=\"token\"\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"{\$v}/key?t={\$token}\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1\r\n" +
            "{\$v}/index.m3u8?token={\$token}&x={\$unknown}\n"

        val resolved = HlsVariables.resolve(text, masterUrl)

        assertThat(resolved).isEqualTo(
            "#EXTM3U\n" +
                "#EXT-X-DEFINE:NAME=\"v\",VALUE=\"hi\"\r\n" +
                "#EXT-X-DEFINE:NAME=\"token\",VALUE=\"a/b\"\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"hi/key?t=a/b\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1\r\n" +
                "hi/index.m3u8?token=a/b&x={\$unknown}\n",
        )
    }

    @Test
    fun `a playlist without definitions is left alone`() {
        val text = "#EXTM3U\n#EXTINF:4,\nseg{\$n}.ts\n"
        assertThat(HlsVariables.resolve(text, masterUrl)).isSameInstanceAs(text)
        assertThat(HlsVariables.substitute("seg{\$n}.ts", emptyMap())).isEqualTo("seg{\$n}.ts")
    }

    @Test
    fun `a value that would break the line or the attribute is not used`() {
        val text = "#EXTM3U\n#EXT-X-DEFINE:QUERYPARAM=\"q\"\n#EXTINF:4,\nseg.ts?q={\$q}\n"
        assertThat(HlsVariables.resolve(text, "https://cdn.example/a.m3u8?q=%22%0A")).isSameInstanceAs(text)
    }

    @Test
    fun `an import missing from the multivariant playlist stays an import`() {
        val text = "#EXTM3U\n#EXT-X-DEFINE:IMPORT=\"cdn\"\n#EXT-X-DEFINE:NAME=\"n\",VALUE=\"1\"\n#EXTINF:4,\n{\$cdn}/seg{\$n}.ts\n"
        assertThat(HlsVariables.resolve(text, masterUrl)).isEqualTo(
            "#EXTM3U\n#EXT-X-DEFINE:IMPORT=\"cdn\"\n#EXT-X-DEFINE:NAME=\"n\",VALUE=\"1\"\n#EXTINF:4,\n{\$cdn}/seg1.ts\n",
        )
    }

    @Test
    fun `the parser lists the variant URLs players will request`() {
        val text = "#EXTM3U\n#EXT-X-DEFINE:QUERYPARAM=\"token\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\n720/index.m3u8?token={\$token}\n"
        val master = assertIs<HlsPlaylist.Master>(HlsParser.parse(text, masterUrl))
        assertThat(master.variants.single().url).isEqualTo("https://cdn.example/hls/720/index.m3u8?token=a/b")
    }
}
