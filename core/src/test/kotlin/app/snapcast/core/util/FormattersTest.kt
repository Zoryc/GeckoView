package app.snapcast.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class FormattersTest {
    @Test
    fun `fileSize uses binary units with one decimal`() {
        assertThat(Formatters.fileSize(0)).isEqualTo("0 B")
        assertThat(Formatters.fileSize(512)).isEqualTo("512 B")
        assertThat(Formatters.fileSize(1023)).isEqualTo("1023 B")
        assertThat(Formatters.fileSize(1024)).isEqualTo("1 KB")
        assertThat(Formatters.fileSize(1536)).isEqualTo("1.5 KB")
        assertThat(Formatters.fileSize(12 * 1024)).isEqualTo("12 KB")
        assertThat(Formatters.fileSize(730L * 1024 * 1024)).isEqualTo("730 MB")
        assertThat(Formatters.fileSize((1.4 * 1024 * 1024 * 1024).toLong())).isEqualTo("1.4 GB")
        assertThat(Formatters.fileSize(3L * 1024 * 1024 * 1024 * 1024)).isEqualTo("3 TB")
    }

    @Test
    fun `fileSize carries rounding into the next unit`() {
        assertThat(Formatters.fileSize(1024L * 1024 - 1)).isEqualTo("1 MB")
    }

    @Test
    fun `fileSize clamps negative values`() {
        assertThat(Formatters.fileSize(-5)).isEqualTo("0 B")
    }

    @Test
    fun `fileSize ignores the default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertThat(Formatters.fileSize(1536)).isEqualTo("1.5 KB")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `duration formats minutes and hours`() {
        assertThat(Formatters.duration(0.0)).isEqualTo("0:00")
        assertThat(Formatters.duration(5.9)).isEqualTo("0:05")
        assertThat(Formatters.duration(245.0)).isEqualTo("4:05")
        assertThat(Formatters.duration(3599.0)).isEqualTo("59:59")
        assertThat(Formatters.duration(3723.0)).isEqualTo("1:02:03")
        assertThat(Formatters.duration(36000.0)).isEqualTo("10:00:00")
    }

    @Test
    fun `duration handles invalid values`() {
        assertThat(Formatters.duration(-1.0)).isEqualTo("0:00")
        assertThat(Formatters.duration(Double.NaN)).isEqualTo("0:00")
        assertThat(Formatters.duration(Double.POSITIVE_INFINITY)).isEqualTo("0:00")
    }

    @Test
    fun `durationMs converts milliseconds`() {
        assertThat(Formatters.durationMs(65_000)).isEqualTo("1:05")
        assertThat(Formatters.durationMs(3_723_000)).isEqualTo("1:02:03")
    }
}
