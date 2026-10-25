package app.snapcast.addons.install

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.assertFailsWith
import org.junit.Test

class BoundedCopyTest {

    @Test
    fun `copies everything within the limit`() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val copied = BoundedCopy.copy(ByteArrayInputStream(data), out, maxBytes = data.size.toLong())
        assertThat(copied).isEqualTo(data.size.toLong())
        assertThat(out.toByteArray()).isEqualTo(data)
    }

    @Test
    fun `fails once the limit is exceeded`() {
        val data = ByteArray(1_000)
        assertFailsWith<IOException> {
            BoundedCopy.copy(ByteArrayInputStream(data), ByteArrayOutputStream(), maxBytes = 999)
        }
    }

    @Test
    fun `empty input copies nothing`() {
        assertThat(BoundedCopy.copy(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), maxBytes = 0)).isEqualTo(0L)
    }
}
