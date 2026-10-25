package app.snapcast.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColorHexTest {
    @Test
    fun `normalizes common spellings`() {
        assertThat(ColorHex.normalize("#AABBCC")).isEqualTo("#aabbcc")
        assertThat(ColorHex.normalize("aabbcc")).isEqualTo("#aabbcc")
        assertThat(ColorHex.normalize(" #abc ")).isEqualTo("#aabbcc")
    }

    @Test
    fun `rejects invalid colors`() {
        assertThat(ColorHex.normalize(null)).isNull()
        assertThat(ColorHex.normalize("")).isNull()
        assertThat(ColorHex.normalize("#abcd")).isNull()
        assertThat(ColorHex.normalize("#gggggg")).isNull()
        assertThat(ColorHex.normalize("red")).isNull()
    }

    @Test
    fun `converts to and from argb`() {
        assertThat(ColorHex.toArgb("#ff8000")).isEqualTo(0xFFFF8000.toInt())
        assertThat(ColorHex.toArgb("nope")).isNull()
        assertThat(ColorHex.fromArgb(0x80123456.toInt())).isEqualTo("#123456")
        assertThat(ColorHex.fromArgb(0xFF000000.toInt())).isEqualTo("#000000")
    }
}
