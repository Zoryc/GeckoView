package app.snapcast.proxy.http

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ByteRangeTest {

    @Test
    fun `parses bounded open and suffix ranges`() {
        assertThat(ByteRange.parse("bytes=0-499")).isEqualTo(ByteRange.FromTo(0, 499))
        assertThat(ByteRange.parse("bytes=500-")).isEqualTo(ByteRange.FromTo(500, null))
        assertThat(ByteRange.parse("bytes=-500")).isEqualTo(ByteRange.Suffix(500))
    }

    @Test
    fun `tolerates whitespace and unit case`() {
        assertThat(ByteRange.parse("  Bytes = 10 - 20 ")).isEqualTo(ByteRange.FromTo(10, 20))
        assertThat(ByteRange.parse("BYTES=7-")).isEqualTo(ByteRange.FromTo(7, null))
    }

    @Test
    fun `multi-range requests use the first range`() {
        assertThat(ByteRange.parse("bytes=0-99,200-299")).isEqualTo(ByteRange.FromTo(0, 99))
        assertThat(ByteRange.parse("bytes=, -10, 0-5")).isEqualTo(ByteRange.Suffix(10))
    }

    @Test
    fun `rejects invalid headers`() {
        listOf(
            null, "", "bytes", "bytes=", "bytes=-", "bytes=abc", "bytes=5-3", "items=0-10", "bytes=1-2-3",
            "bytes=+1-5", "bytes=0x10-", "bytes=99999999999999999999-",
        ).forEach { header ->
            assertThat(ByteRange.parse(header)).isNull()
        }
    }

    @Test
    fun `resolves bounded ranges against the total length`() {
        assertThat(ByteRange.FromTo(0, 499).resolve(1000)).isEqualTo(ResolvedRange(0, 499, 1000))
        // The end is clamped to the last byte.
        assertThat(ByteRange.FromTo(900, 5000).resolve(1000)).isEqualTo(ResolvedRange(900, 999, 1000))
        assertThat(ByteRange.FromTo(500, null).resolve(1000)).isEqualTo(ResolvedRange(500, 999, 1000))
    }

    @Test
    fun `resolves suffix ranges`() {
        assertThat(ByteRange.Suffix(100).resolve(1000)).isEqualTo(ResolvedRange(900, 999, 1000))
        // A suffix longer than the representation selects all of it.
        assertThat(ByteRange.Suffix(5000).resolve(1000)).isEqualTo(ResolvedRange(0, 999, 1000))
    }

    @Test
    fun `unsatisfiable ranges resolve to null`() {
        assertThat(ByteRange.FromTo(1000, null).resolve(1000)).isNull()
        assertThat(ByteRange.FromTo(2000, 3000).resolve(1000)).isNull()
        assertThat(ByteRange.Suffix(0).resolve(1000)).isNull()
        assertThat(ByteRange.Suffix(10).resolve(0)).isNull()
        assertThat(ByteRange.FromTo(0, null).resolve(0)).isNull()
    }

    @Test
    fun `resolved range exposes length and content range`() {
        val range = ResolvedRange(100, 199, 1000)
        assertThat(range.length).isEqualTo(100)
        assertThat(range.contentRange).isEqualTo("bytes 100-199/1000")
        assertThat(ResolvedRange(0, 0, 1).length).isEqualTo(1)
    }

    @Test
    fun `header values round trip`() {
        listOf("bytes=0-499", "bytes=500-", "bytes=-500").forEach { header ->
            assertThat(ByteRange.parse(header)!!.headerValue).isEqualTo(header)
        }
    }

    @Test
    fun `parses content range headers`() {
        assertThat(ContentRange.parse("bytes 0-0/5000")).isEqualTo(ContentRange(0, 0, 5000))
        assertThat(ContentRange.parse("bytes 100-199/*")).isEqualTo(ContentRange(100, 199, null))
        assertThat(ContentRange.parse("bytes */1234")).isEqualTo(ContentRange(null, null, 1234))
        assertThat(ContentRange.parse("bytes=0-9/10")).isEqualTo(ContentRange(0, 9, 10))
        assertThat(ContentRange.parse("bytes 9-0/10")).isNull()
        assertThat(ContentRange.parse("items 0-1/2")).isNull()
        assertThat(ContentRange.parse("bytes 0-1")).isNull()
        assertThat(ContentRange.parse(null)).isNull()
    }
}
