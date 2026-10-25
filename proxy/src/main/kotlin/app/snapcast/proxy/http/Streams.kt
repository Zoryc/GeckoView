package app.snapcast.proxy.http

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [onClose] exactly once, when the stream is closed or its end is reached, so an upstream
 * response is released as soon as its body is done or the client goes away. Once closed it reads as
 * an empty stream (wrappers such as PushbackInputStream may read again after the end).
 */
internal class ClosingInputStream(source: InputStream, private val onClose: () -> Unit) : FilterInputStream(source) {
    private val closed = AtomicBoolean(false)

    override fun read(): Int {
        if (closed.get()) return -1
        val value = super.read()
        if (value < 0) close()
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed.get()) return -1
        val count = super.read(b, off, len)
        if (count < 0) close()
        return count
    }

    override fun skip(n: Long): Long = if (closed.get()) 0 else super.skip(n)

    override fun available(): Int = if (closed.get()) 0 else super.available()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            super.close()
        } finally {
            onClose()
        }
    }
}

/** Exposes at most [limit] bytes of [source]; closing it closes [source]. */
internal class LimitedInputStream(source: InputStream, limit: Long) : FilterInputStream(source) {
    private var remaining = limit

    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        if (len == 0) return 0
        val count = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, remaining))
        if (skipped > 0) remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

    override fun markSupported(): Boolean = false
}

/** Thrown when a body that must be held in memory (a playlist, a subtitle) exceeds its size cap. */
internal class BodyTooLargeException(limit: Long) : IOException("Body exceeds $limit bytes")

internal object Streams {
    /** A fresh empty stream (InputStream.nullInputStream() needs Android 13). */
    fun empty(): InputStream = ByteArrayInputStream(ByteArray(0))

    /** Skips exactly [count] bytes, reading when [InputStream.skip] makes no progress. */
    fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        var buffer: ByteArray? = null
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val scratch = buffer ?: ByteArray(16 * 1024).also { buffer = it }
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Stream ended $remaining bytes before the range start")
            remaining -= read
        }
    }

    /** Reads [input] to the end; throws [BodyTooLargeException] beyond [limit] bytes. */
    fun readCapped(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw BodyTooLargeException(limit)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Reads up to [count] bytes (fewer only at the end of the stream). */
    fun readUpTo(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = input.read(buffer, filled, count - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == count) buffer else buffer.copyOf(filled)
    }
}
