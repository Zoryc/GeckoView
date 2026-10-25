package app.snapcast.proxy.http

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.PushbackInputStream
import kotlin.test.assertFailsWith

class StreamsTest {
    private val data = ByteArray(100) { it.toByte() }

    @Test
    fun `closing stream releases once at the end and reads empty afterwards`() {
        var released = 0
        val stream = ClosingInputStream(ByteArrayInputStream(data)) { released++ }
        assertThat(stream.readBytes()).isEqualTo(data)
        assertThat(released).isEqualTo(1)
        assertThat(stream.read()).isEqualTo(-1)
        stream.close()
        assertThat(released).isEqualTo(1)
    }

    @Test
    fun `closing stream releases when closed early`() {
        var released = 0
        val stream = ClosingInputStream(ByteArrayInputStream(data)) { released++ }
        stream.read(ByteArray(10))
        stream.close()
        stream.close()
        assertThat(released).isEqualTo(1)
    }

    @Test
    fun `pushback over a stream closed at its end still yields the pushed bytes`() {
        val pushback = PushbackInputStream(ClosingInputStream(ByteArrayInputStream(data)) {}, 4096)
        val head = Streams.readUpTo(pushback, 4096)
        assertThat(head).isEqualTo(data)
        pushback.unread(head)
        assertThat(pushback.readBytes()).isEqualTo(data)
    }

    @Test
    fun `limited stream stops at its limit`() {
        val limited = LimitedInputStream(ByteArrayInputStream(data), 30)
        assertThat(limited.readBytes()).isEqualTo(data.copyOf(30))
        assertThat(limited.read()).isEqualTo(-1)
    }

    @Test
    fun `skipFully skips exactly or fails at the end`() {
        val input = ByteArrayInputStream(data)
        Streams.skipFully(input, 60)
        assertThat(input.read()).isEqualTo(60)
        assertFailsWith<EOFException> { Streams.skipFully(ByteArrayInputStream(data), 101) }
    }

    @Test
    fun `readCapped enforces its limit`() {
        assertThat(Streams.readCapped(ByteArrayInputStream(data), 100)).isEqualTo(data)
        assertFailsWith<BodyTooLargeException> { Streams.readCapped(ByteArrayInputStream(data), 99) }
    }
}
