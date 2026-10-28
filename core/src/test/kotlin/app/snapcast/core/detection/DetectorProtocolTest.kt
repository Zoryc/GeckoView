package app.snapcast.core.detection

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertIs

class DetectorProtocolTest {
    @Test
    fun `decodes hello`() {
        val message = DetectorProtocol.decode("""{"type":"hello","url":"https://e.com/","title":"Home"}""")
        assertThat(message).isEqualTo(HelloMessage(url = "https://e.com/", title = "Home"))
        assertThat(DetectorProtocol.decode("""{"type":"hello","url":"https://e.com/"}""")).isEqualTo(HelloMessage("https://e.com/"))
    }

    @Test
    fun `decodes page`() {
        val message = DetectorProtocol.decode(
            """{"type":"page","url":"https://e.com/v","title":"T","ogTitle":"OG","ogImage":"https://e.com/i.jpg","extra":1}""",
        )
        assertThat(message).isEqualTo(PageMessage("https://e.com/v", "T", "OG", "https://e.com/i.jpg"))
    }

    @Test
    fun `decodes media with all report fields`() {
        val text = """
            {"type":"media","items":[{
              "url":"https://cdn.e.com/master.m3u8","source":"network","mime":"application/vnd.apple.mpegurl",
              "size":1234,"status":200,"resourceType":"xmlhttprequest","frameUrl":"https://player.e.com/embed",
              "pageUrl":"https://e.com/watch","pageTitle":"Watch","requestHeaders":{"Referer":"https://player.e.com/","Cookie":"a=b"},
              "cookiesByHost":{"seg.e.com":"c=d"},"duration":null,"width":1280,"height":720,"poster":"https://e.com/p.jpg",
              "title":"Movie","isBlob":false,
              "tracks":[{"url":"https://e.com/en.vtt","kind":"subtitles","lang":"en","label":"English"}],
              "timestamp":1700000000000,"unknownField":true
            },{"url":"blob:https://e.com/abc","source":"dom","isBlob":true}]}
        """.trimIndent()
        val message = assertIs<MediaMessage>(DetectorProtocol.decode(text))
        assertThat(message.items).hasSize(2)
        val report = message.items[0]
        assertThat(report.url).isEqualTo("https://cdn.e.com/master.m3u8")
        assertThat(report.source).isEqualTo("network")
        assertThat(report.size).isEqualTo(1234L)
        assertThat(report.status).isEqualTo(200)
        assertThat(report.resourceType).isEqualTo("xmlhttprequest")
        assertThat(report.requestHeaders).containsExactly("Referer", "https://player.e.com/", "Cookie", "a=b")
        assertThat(report.cookiesByHost).containsExactly("seg.e.com", "c=d")
        assertThat(report.duration).isNull()
        assertThat(report.width).isEqualTo(1280)
        assertThat(report.tracks).containsExactly(TrackReport("https://e.com/en.vtt", "subtitles", "en", "English"))
        assertThat(report.timestamp).isEqualTo(1_700_000_000_000L)
        val blob = message.items[1]
        assertThat(blob.isBlob).isTrue()
        assertThat(blob.requestHeaders).isEmpty()
        assertThat(blob.tracks).isEmpty()
    }

    @Test
    fun `decodes drm`() {
        assertThat(DetectorProtocol.decode("""{"type":"drm","keySystem":"com.widevine.alpha","frameUrl":"https://e.com/f"}"""))
            .isEqualTo(DrmMessage("com.widevine.alpha", "https://e.com/f"))
        assertThat(DetectorProtocol.decode("""{"type":"drm"}""")).isEqualTo(DrmMessage())
    }

    @Test
    fun `discriminator can appear after the payload`() {
        assertThat(DetectorProtocol.decode("""{"url":"https://e.com/","type":"hello"}""")).isEqualTo(HelloMessage("https://e.com/"))
    }

    @Test
    fun `unknown types return null`() {
        assertThat(DetectorProtocol.decode("""{"type":"bogus","url":"x"}""")).isNull()
        assertThat(DetectorProtocol.decode("""{"type":"rescan"}""")).isNull()
        assertThat(DetectorProtocol.decode("""{"url":"https://e.com/"}""")).isNull()
    }

    @Test
    fun `malformed input returns null`() {
        assertThat(DetectorProtocol.decode("")).isNull()
        assertThat(DetectorProtocol.decode("not json")).isNull()
        assertThat(DetectorProtocol.decode("""{"type":"hello"""")).isNull()
        assertThat(DetectorProtocol.decode("""["hello"]""")).isNull()
        assertThat(DetectorProtocol.decode("null")).isNull()
        assertThat(DetectorProtocol.decode("""{"type":"hello"}""")).isNull()
        assertThat(DetectorProtocol.decode("""{"type":"media","items":"nope"}""")).isNull()
        assertThat(DetectorProtocol.decode("""{"type":"media","items":[{"url":"x","source":"dom","size":"big"}]}""")).isNull()
    }

    @Test
    fun `encodes rescan command`() {
        val encoded = DetectorProtocol.encode(RescanCommand)
        val obj = Json.parseToJsonElement(encoded) as JsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("rescan")
        assertThat(obj.keys).containsExactly("type")
    }

    @Test
    fun `encodes refresh and thumbnails commands`() {
        val refresh = Json.parseToJsonElement(DetectorProtocol.encode(RefreshCommand)) as JsonObject
        assertThat(refresh["type"]?.jsonPrimitive?.content).isEqualTo("refresh")
        assertThat(refresh.keys).containsExactly("type")
        val thumbnails = Json.parseToJsonElement(DetectorProtocol.encode(ThumbnailsCommand(3))) as JsonObject
        assertThat(thumbnails["type"]?.jsonPrimitive?.content).isEqualTo("thumbnails")
        assertThat(thumbnails["generation"]?.jsonPrimitive?.content).isEqualTo("3")
    }

    @Test
    fun `serial names match the extension protocol`() {
        fun typeOf(message: DetectorMessage): String {
            val obj = DetectorProtocol.json.encodeToJsonElement(DetectorMessage.serializer(), message) as JsonObject
            return obj["type"]!!.jsonPrimitive.content
        }
        assertThat(typeOf(HelloMessage("u"))).isEqualTo("hello")
        assertThat(typeOf(PageMessage("u"))).isEqualTo("page")
        assertThat(typeOf(MediaMessage(emptyList()))).isEqualTo("media")
        assertThat(typeOf(DrmMessage())).isEqualTo("drm")
    }

    @Test
    fun `constants`() {
        assertThat(NATIVE_APP_NAME).isEqualTo("snapcast")
        assertThat(DETECTOR_EXTENSION_ID).isEqualTo("detector@snapcast.app")
        assertThat(DETECTOR_EXTENSION_LOCATION).endsWith("/")
    }
}
