package app.snapcast.detection.inspect

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

class ProgressiveProbeTest {
    @Test
    fun `size comes from Content-Length of a complete response`() {
        val result = ProgressiveProbe.fromResponse(200, "734003200", null, "video/mp4", null)
        assertThat(result).isEqualTo(InspectionResult(sizeBytes = 734_003_200L, mimeType = "video/mp4"))
    }

    @Test
    fun `size comes from the Content-Range total of a range response`() {
        val result = ProgressiveProbe.fromResponse(206, "1", "bytes 0-0/6442450944", "application/octet-stream", null)
        assertThat(result).isEqualTo(InspectionResult(sizeBytes = 6_442_450_944L))
        assertThat(ProgressiveProbe.fromResponse(206, "1", "bytes 0-0/*", "video/mp4", null)).isNull()
        assertThat(ProgressiveProbe.fromResponse(206, "1", null, "video/mp4", null)).isNull()
    }

    @Test
    fun `responses that do not describe the file are ignored`() {
        assertThat(ProgressiveProbe.fromResponse(403, "100", null, "video/mp4", null)).isNull()
        assertThat(ProgressiveProbe.fromResponse(200, "5120", null, "text/html; charset=utf-8", null)).isNull()
        assertThat(ProgressiveProbe.fromResponse(200, "5120", null, "application/json", null)).isNull()
        assertThat(ProgressiveProbe.fromResponse(200, "5120", null, "image/jpeg", null)).isNull()
        assertThat(ProgressiveProbe.fromResponse(200, "5120", null, "video/mp4", "gzip")).isNull()
        assertThat(ProgressiveProbe.fromResponse(200, "0", null, "video/mp4", null)).isNull()
        assertThat(ProgressiveProbe.fromResponse(200, "abc", null, "video/mp4", null)).isNull()
        assertThat(ProgressiveProbe.fromResponse(200, null, null, null, null)).isNull()
    }

    @Test
    fun `identity encoding and generic types are fine`() {
        assertThat(ProgressiveProbe.fromResponse(200, " 42 ", null, null, "identity")).isEqualTo(InspectionResult(sizeBytes = 42L))
    }

    @Test
    fun `Content-Range totals`() {
        assertThat(ProgressiveProbe.totalFromContentRange("bytes 100-199/1000")).isEqualTo(1000L)
        assertThat(ProgressiveProbe.totalFromContentRange("BYTES */1000")).isEqualTo(1000L)
        assertThat(ProgressiveProbe.totalFromContentRange("bytes 0-1")).isNull()
        assertThat(ProgressiveProbe.totalFromContentRange(null)).isNull()
    }

    @Test
    fun `readLimited stops at the limit and tells whether the body was complete`() {
        val text = "#EXTM3U\n#EXTINF:6,\nseg-1.ts\n"
        val whole = readLimited(ByteArrayInputStream(text.toByteArray()), 1024)
        assertThat(whole).isEqualTo(LimitedText(text, complete = true))

        val exact = readLimited(ByteArrayInputStream(text.toByteArray()), text.length)
        assertThat(exact).isEqualTo(LimitedText(text, complete = true))

        val cut = readLimited(ByteArrayInputStream(ByteArray(100_000) { 'a'.code.toByte() }), 40_000)
        assertThat(cut.text.length).isEqualTo(40_000)
        assertThat(cut.complete).isFalse()
    }
}
