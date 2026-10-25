package app.snapcast.proxy

import app.snapcast.core.model.SubtitleFormat
import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local sources with Range support and subtitles served as WebVTT. */
class StreamProxyLocalAndSubtitleTest {
    private val harness = ProxyTestHarness()

    @After
    fun tearDown() = harness.close()

    private class MemorySource(private val bytes: ByteArray, override val length: Long? = bytes.size.toLong()) : LocalSource {
        override val mimeType: String = "video/mp4"
        val opened = CopyOnWriteArrayList<Long>()

        override fun open(offset: Long): InputStream {
            opened += offset
            return ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }
    }

    @Test
    fun `local source is served whole and by range`() {
        val bytes = ProxyTestHarness.bytes(10_000)
        val source = MemorySource(bytes)
        val local = harness.proxy.registerLocal(source, "My Holiday Video.mp4")
        assertThat(local.path).isEqualTo("/local/${local.id}/My_Holiday_Video.mp4")

        val whole = harness.fetch(local.path)
        assertThat(whole.code).isEqualTo(200)
        assertThat(whole.header("Content-Length")).isEqualTo("10000")
        assertThat(whole.header("Accept-Ranges")).isEqualTo("bytes")
        assertThat(whole.header("Content-Type")).isEqualTo("video/mp4")
        assertThat(whole.body).isEqualTo(bytes)

        val middle = harness.fetch(local.path, mapOf("Range" to "bytes=2000-2999"))
        assertThat(middle.code).isEqualTo(206)
        assertThat(middle.header("Content-Range")).isEqualTo("bytes 2000-2999/10000")
        assertThat(middle.header("Content-Length")).isEqualTo("1000")
        assertThat(middle.body).isEqualTo(bytes.copyOfRange(2000, 3000))

        val tail = harness.fetch(local.path, mapOf("Range" to "bytes=-100"))
        assertThat(tail.header("Content-Range")).isEqualTo("bytes 9900-9999/10000")
        assertThat(tail.body).isEqualTo(bytes.copyOfRange(9900, 10_000))

        val open = harness.fetch(local.path, mapOf("Range" to "bytes=9990-"))
        assertThat(open.body).isEqualTo(bytes.copyOfRange(9990, 10_000))

        assertThat(source.opened).containsExactly(0L, 2000L, 9900L, 9990L).inOrder()
    }

    @Test
    fun `local source answers 416 and HEAD without opening the file`() {
        val source = MemorySource(ProxyTestHarness.bytes(500))
        val local = harness.proxy.registerLocal(source, "clip.mp4")

        val unsatisfiable = harness.fetch(local.path, mapOf("Range" to "bytes=500-"))
        assertThat(unsatisfiable.code).isEqualTo(416)
        assertThat(unsatisfiable.header("Content-Range")).isEqualTo("bytes */500")

        val head = harness.fetch(local.path, method = "HEAD")
        assertThat(head.code).isEqualTo(200)
        assertThat(head.header("Content-Length")).isEqualTo("500")

        val headRange = harness.fetch(local.path, mapOf("Range" to "bytes=100-"), method = "HEAD")
        assertThat(headRange.code).isEqualTo(206)
        assertThat(headRange.header("Content-Length")).isEqualTo("400")
        assertThat(source.opened).isEmpty()
    }

    @Test
    fun `a client disconnect closes the source stream`() {
        val closed = CountDownLatch(1)
        val endless = object : LocalSource {
            override val mimeType = "video/mp4"
            override val length: Long = 1L shl 32
            override fun open(offset: Long): InputStream = object : InputStream() {
                override fun read(): Int = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int = len
                override fun close() = closed.countDown()
            }
        }
        val local = harness.proxy.registerLocal(endless, "huge.mp4")

        Socket("127.0.0.1", harness.proxy.port).use { socket ->
            socket.getOutputStream().write("GET ${local.path} HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
            socket.getInputStream().readNBytes(64 * 1024)
        }

        assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `local source of unknown length is streamed with 200`() {
        val bytes = ProxyTestHarness.bytes(3000)
        val local = harness.proxy.registerLocal(MemorySource(bytes, length = null), "stream.ts")

        val response = harness.fetch(local.path, mapOf("Range" to "bytes=100-"))

        assertThat(response.code).isEqualTo(200)
        assertThat(response.header("Content-Length")).isNull()
        assertThat(response.body).isEqualTo(bytes)
    }

    @Test
    fun `local source failures answer 500`() {
        val failing = object : LocalSource {
            override val mimeType = "video/mp4"
            override val length: Long = 100
            override fun open(offset: Long): InputStream = throw IOException("revoked")
        }
        val local = harness.proxy.registerLocal(failing, "x.mp4")

        assertThat(harness.fetch(local.path).code).isEqualTo(500)
    }

    @Test
    fun `inline SRT is served as WebVTT`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello <font color=\"red\">world</font>\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nBye\r\n"
        val subtitle = harness.proxy.registerSubtitle(SubtitleSource.Inline(srt, SubtitleFormat.SRT))
        assertThat(subtitle.path).isEqualTo("/sub/${subtitle.id}.vtt")

        val response = harness.fetch(subtitle.path)

        assertThat(response.code).isEqualTo(200)
        assertThat(response.header("Content-Type")).isEqualTo("text/vtt; charset=utf-8")
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo("*")
        assertThat(response.text).startsWith("WEBVTT\n")
        assertThat(response.text).contains("00:00:01.000 --> 00:00:02.500\nHello world\n")
        assertThat(response.text).contains("00:00:03.000 --> 00:00:04.000\nBye\n")
    }

    @Test
    fun `remote SRT is fetched with its headers and converted`() {
        val srt = "1\n00:00:05,000 --> 00:00:06,000\nCafé\n"
        harness.origin.on("/subs/en.srt") {
            MockResponse.Builder().code(200)
                .setHeader("Content-Type", "application/x-subrip")
                .body(Buffer().write(srt.toByteArray(charset("windows-1252"))))
                .build()
        }
        val subtitle = harness.proxy.registerSubtitle(
            SubtitleSource.Remote(
                harness.origin.url("/subs/en.srt"),
                mapOf("Referer" to "https://page.example/", "Cookie" to "s=1"),
                SubtitleFormat.SRT,
            ),
        )

        val response = harness.fetch(subtitle.path)

        assertThat(response.code).isEqualTo(200)
        assertThat(response.text).isEqualTo("WEBVTT\n\n1\n00:00:05.000 --> 00:00:06.000\nCafé\n")
        val upstream = harness.origin.requestsTo("/subs/en.srt").single().headers
        assertThat(upstream["Referer"]).isEqualTo("https://page.example/")
        assertThat(upstream["Cookie"]).isEqualTo("s=1")
    }

    @Test
    fun `remote VTT passes through and upstream errors keep their status`() {
        harness.origin.text("/subs/fr.vtt", "WEBVTT\n\n00:01.000 --> 00:02.000\nSalut\n", "text/vtt")
        val ok = harness.proxy.registerSubtitle(SubtitleSource.Remote(harness.origin.url("/subs/fr.vtt"), emptyMap(), SubtitleFormat.VTT))
        val missing = harness.proxy.registerSubtitle(SubtitleSource.Remote(harness.origin.url("/subs/none.vtt"), emptyMap(), SubtitleFormat.VTT))

        assertThat(harness.fetch(ok.path).text).isEqualTo("WEBVTT\n\n00:01.000 --> 00:02.000\nSalut\n")
        assertThat(harness.fetch(missing.path).code).isEqualTo(404)
        assertThat(harness.fetch(ok.path, method = "HEAD").header("Content-Length"))
            .isEqualTo("WEBVTT\n\n00:01.000 --> 00:02.000\nSalut\n".length.toString())
    }
}
