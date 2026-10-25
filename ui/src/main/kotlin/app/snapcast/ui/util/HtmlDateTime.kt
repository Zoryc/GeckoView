package app.snapcast.ui.util

import app.snapcast.browser.BrowserPrompt.DateTimeType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * Conversions between HTML date/time input values and java.time values:
 * date `yyyy-MM-dd`, month `yyyy-MM`, week `yyyy-Www`, time `HH:mm[:ss[.SSS]]`,
 * datetime-local `yyyy-MM-ddTHH:mm[:ss]`.
 */
object HtmlDateTime {
    /** Parsed form value; either part may be null when absent or unparsable. */
    data class Value(val date: LocalDate?, val time: LocalTime?)

    fun parse(type: DateTimeType, value: String?): Value {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return Value(null, null)
        return when (type) {
            DateTimeType.DATE -> Value(parseDate(text), null)
            DateTimeType.MONTH -> Value(parseMonth(text), null)
            DateTimeType.WEEK -> Value(parseWeek(text), null)
            DateTimeType.TIME -> Value(null, parseTime(text))
            DateTimeType.DATETIME_LOCAL -> parseDateTime(text)?.let { Value(it.toLocalDate(), it.toLocalTime()) }
                ?: Value(null, null)
        }
    }

    /**
     * Formats a picked value for [type]. Returns null when a part required by [type] is missing
     * (e.g. no time for TIME).
     */
    fun format(type: DateTimeType, date: LocalDate?, time: LocalTime?): String? = when (type) {
        DateTimeType.DATE -> date?.let(::formatDate)
        DateTimeType.MONTH -> date?.let { "%04d-%02d".format(Locale.ROOT, it.year, it.monthValue) }
        DateTimeType.WEEK -> date?.let {
            "%04d-W%02d".format(Locale.ROOT, it.get(IsoFields.WEEK_BASED_YEAR), it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        }
        DateTimeType.TIME -> time?.let(::formatTime)
        DateTimeType.DATETIME_LOCAL -> if (date != null && time != null) "${formatDate(date)}T${formatTime(time)}" else null
    }

    /** True when [type] needs a date picker. */
    fun needsDate(type: DateTimeType): Boolean = type != DateTimeType.TIME

    /** True when [type] needs a time picker. */
    fun needsTime(type: DateTimeType): Boolean = type == DateTimeType.TIME || type == DateTimeType.DATETIME_LOCAL

    /**
     * Whether [date] lies within the input's min/max (values in the format of [type]); a missing or
     * unparsable bound does not restrict. For months and weeks the whole max period is allowed.
     */
    fun isDateAllowed(type: DateTimeType, date: LocalDate, min: String?, max: String?): Boolean {
        val lower = parse(type, min).date
        val upperStart = parse(type, max).date
        val upper = upperStart?.let {
            when (type) {
                DateTimeType.MONTH -> YearMonth.from(it).atEndOfMonth()
                DateTimeType.WEEK -> it.plusDays(6)
                else -> it
            }
        }
        if (lower != null && date.isBefore(lower)) return false
        if (upper != null && date.isAfter(upper)) return false
        return true
    }

    /** UTC midnight of [date] in epoch millis (the Material date picker works in UTC). */
    fun toUtcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromUtcMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    private fun formatDate(date: LocalDate): String = "%04d-%02d-%02d".format(Locale.ROOT, date.year, date.monthValue, date.dayOfMonth)

    private fun formatTime(time: LocalTime): String = "%02d:%02d".format(Locale.ROOT, time.hour, time.minute)

    private fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun parseMonth(text: String): LocalDate? = try {
        YearMonth.parse(text).atDay(1)
    } catch (e: DateTimeParseException) {
        null
    }

    private val weekPattern = Regex("""^(\d{4,})-W(\d{2})$""", RegexOption.IGNORE_CASE)

    private fun parseWeek(text: String): LocalDate? {
        val match = weekPattern.matchEntire(text) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val week = match.groupValues[2].toIntOrNull() ?: return null
        // January 4th is always in ISO week 1 of its week-based year.
        val reference = LocalDate.of(year, 1, 4)
        val maxWeek = reference.range(IsoFields.WEEK_OF_WEEK_BASED_YEAR).maximum
        if (week < 1 || week > maxWeek) return null
        return reference.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong()).with(DayOfWeek.MONDAY)
    }

    private fun parseTime(text: String): LocalTime? = try {
        LocalTime.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun parseDateTime(text: String): LocalDateTime? = try {
        LocalDateTime.parse(text.replace(' ', 'T'))
    } catch (e: DateTimeParseException) {
        null
    }
}
