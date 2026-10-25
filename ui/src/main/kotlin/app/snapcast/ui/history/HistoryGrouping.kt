package app.snapcast.ui.history

import app.snapcast.data.history.HistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The day a group of history entries belongs to. */
internal sealed interface HistoryDay {
    data object Today : HistoryDay

    data object Yesterday : HistoryDay

    data class Date(val date: LocalDate) : HistoryDay
}

/** Entries visited on one calendar day, newest first. */
internal data class HistorySection(val date: LocalDate, val day: HistoryDay, val entries: List<HistoryEntry>) {
    /** Stable list key of the section header. */
    val key: String get() = "day-$date"
}

/** Pure grouping of history entries by local calendar day. */
internal object HistoryGrouping {
    /**
     * Groups [entries] (expected newest first, as the repository emits them) by the local date of
     * [HistoryEntry.visitedAt] in [zone]. Sections keep the order in which their first entry appears, and
     * entries keep their order inside a section.
     */
    fun group(entries: List<HistoryEntry>, today: LocalDate, zone: ZoneId): List<HistorySection> {
        val byDate = LinkedHashMap<LocalDate, MutableList<HistoryEntry>>()
        for (entry in entries) {
            byDate.getOrPut(localDate(entry.visitedAt, zone)) { ArrayList() } += entry
        }
        return byDate.map { (date, items) -> HistorySection(date, dayOf(date, today), items) }
    }

    fun dayOf(date: LocalDate, today: LocalDate): HistoryDay = when (date) {
        today -> HistoryDay.Today
        today.minusDays(1) -> HistoryDay.Yesterday
        else -> HistoryDay.Date(date)
    }

    fun localDate(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
