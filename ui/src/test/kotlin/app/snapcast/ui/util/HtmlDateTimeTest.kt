package app.snapcast.ui.util

import app.snapcast.browser.BrowserPrompt.DateTimeType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class HtmlDateTimeTest {
    @Test
    fun `parses and formats dates`() {
        val value = HtmlDateTime.parse(DateTimeType.DATE, "2024-02-29")
        assertThat(value.date).isEqualTo(LocalDate.of(2024, 2, 29))
        assertThat(HtmlDateTime.format(DateTimeType.DATE, value.date, null)).isEqualTo("2024-02-29")
    }

    @Test
    fun `months use the first day and format as year-month`() {
        val value = HtmlDateTime.parse(DateTimeType.MONTH, "2025-07")
        assertThat(value.date).isEqualTo(LocalDate.of(2025, 7, 1))
        assertThat(HtmlDateTime.format(DateTimeType.MONTH, LocalDate.of(2025, 7, 19), null)).isEqualTo("2025-07")
    }

    @Test
    fun `weeks follow ISO week numbering`() {
        // 2021-01-01 is a Friday in ISO week 53 of 2020.
        assertThat(HtmlDateTime.format(DateTimeType.WEEK, LocalDate.of(2021, 1, 1), null)).isEqualTo("2020-W53")
        assertThat(HtmlDateTime.format(DateTimeType.WEEK, LocalDate.of(2024, 12, 30), null)).isEqualTo("2025-W01")
        assertThat(HtmlDateTime.parse(DateTimeType.WEEK, "2025-W01").date).isEqualTo(LocalDate.of(2024, 12, 30))
        assertThat(HtmlDateTime.parse(DateTimeType.WEEK, "2024-W10").date).isEqualTo(LocalDate.of(2024, 3, 4))
    }

    @Test
    fun `invalid week numbers are rejected`() {
        assertThat(HtmlDateTime.parse(DateTimeType.WEEK, "2021-W53").date).isNull()
        assertThat(HtmlDateTime.parse(DateTimeType.WEEK, "2020-W53").date).isEqualTo(LocalDate.of(2020, 12, 28))
        assertThat(HtmlDateTime.parse(DateTimeType.WEEK, "2020-W00").date).isNull()
    }

    @Test
    fun `times drop seconds when formatting`() {
        val value = HtmlDateTime.parse(DateTimeType.TIME, "07:05:30.250")
        assertThat(value.time).isEqualTo(LocalTime.of(7, 5, 30, 250_000_000))
        assertThat(HtmlDateTime.format(DateTimeType.TIME, null, LocalTime.of(7, 5))).isEqualTo("07:05")
    }

    @Test
    fun `datetime-local combines both parts`() {
        val value = HtmlDateTime.parse(DateTimeType.DATETIME_LOCAL, "2024-05-06T21:30")
        assertThat(value.date).isEqualTo(LocalDate.of(2024, 5, 6))
        assertThat(value.time).isEqualTo(LocalTime.of(21, 30))
        assertThat(HtmlDateTime.format(DateTimeType.DATETIME_LOCAL, value.date, value.time)).isEqualTo("2024-05-06T21:30")
        assertThat(HtmlDateTime.parse(DateTimeType.DATETIME_LOCAL, "2024-05-06 21:30").time).isEqualTo(LocalTime.of(21, 30))
    }

    @Test
    fun `missing required parts format to null`() {
        assertThat(HtmlDateTime.format(DateTimeType.DATETIME_LOCAL, LocalDate.of(2024, 1, 1), null)).isNull()
        assertThat(HtmlDateTime.format(DateTimeType.TIME, LocalDate.of(2024, 1, 1), null)).isNull()
        assertThat(HtmlDateTime.format(DateTimeType.DATE, null, LocalTime.NOON)).isNull()
    }

    @Test
    fun `garbage and empty values parse to nothing`() {
        assertThat(HtmlDateTime.parse(DateTimeType.DATE, "yesterday")).isEqualTo(HtmlDateTime.Value(null, null))
        assertThat(HtmlDateTime.parse(DateTimeType.TIME, "")).isEqualTo(HtmlDateTime.Value(null, null))
        assertThat(HtmlDateTime.parse(DateTimeType.MONTH, null)).isEqualTo(HtmlDateTime.Value(null, null))
    }

    @Test
    fun `picker steps per type`() {
        assertThat(HtmlDateTime.needsDate(DateTimeType.TIME)).isFalse()
        assertThat(HtmlDateTime.needsTime(DateTimeType.TIME)).isTrue()
        assertThat(HtmlDateTime.needsDate(DateTimeType.DATETIME_LOCAL)).isTrue()
        assertThat(HtmlDateTime.needsTime(DateTimeType.DATETIME_LOCAL)).isTrue()
        assertThat(HtmlDateTime.needsTime(DateTimeType.WEEK)).isFalse()
    }

    @Test
    fun `min and max bound the selectable dates`() {
        val type = DateTimeType.DATE
        assertThat(HtmlDateTime.isDateAllowed(type, LocalDate.of(2024, 1, 10), "2024-01-10", "2024-01-20")).isTrue()
        assertThat(HtmlDateTime.isDateAllowed(type, LocalDate.of(2024, 1, 9), "2024-01-10", "2024-01-20")).isFalse()
        assertThat(HtmlDateTime.isDateAllowed(type, LocalDate.of(2024, 1, 21), "2024-01-10", "2024-01-20")).isFalse()
        assertThat(HtmlDateTime.isDateAllowed(type, LocalDate.of(1999, 1, 1), null, "nonsense")).isTrue()
    }

    @Test
    fun `month and week maxima include the whole period`() {
        assertThat(HtmlDateTime.isDateAllowed(DateTimeType.MONTH, LocalDate.of(2024, 2, 29), null, "2024-02")).isTrue()
        assertThat(HtmlDateTime.isDateAllowed(DateTimeType.MONTH, LocalDate.of(2024, 3, 1), null, "2024-02")).isFalse()
        assertThat(HtmlDateTime.isDateAllowed(DateTimeType.WEEK, LocalDate.of(2024, 3, 10), null, "2024-W10")).isTrue()
        assertThat(HtmlDateTime.isDateAllowed(DateTimeType.WEEK, LocalDate.of(2024, 3, 11), null, "2024-W10")).isFalse()
    }

    @Test
    fun `utc millis round trip`() {
        val date = LocalDate.of(2023, 11, 5)
        assertThat(HtmlDateTime.fromUtcMillis(HtmlDateTime.toUtcMillis(date))).isEqualTo(date)
        assertThat(HtmlDateTime.toUtcMillis(LocalDate.of(1970, 1, 2))).isEqualTo(86_400_000L)
    }
}
