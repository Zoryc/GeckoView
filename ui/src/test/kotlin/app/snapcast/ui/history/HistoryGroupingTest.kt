package app.snapcast.ui.history

import app.snapcast.data.history.HistoryEntry
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class HistoryGroupingTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 10, 25)

    private fun entry(id: Long, at: LocalDateTime) =
        HistoryEntry(id = id, url = "https://example.com/$id", title = "Page $id", visitedAt = at.toInstant(ZoneOffset.UTC).toEpochMilli())

    @Test
    fun `groups newest-first entries into today, yesterday and older dates`() {
        val entries = listOf(
            entry(1, LocalDateTime.of(2026, 10, 25, 22, 0)),
            entry(2, LocalDateTime.of(2026, 10, 25, 0, 0)),
            entry(3, LocalDateTime.of(2026, 10, 24, 23, 59, 59)),
            entry(4, LocalDateTime.of(2026, 10, 20, 12, 0)),
            entry(5, LocalDateTime.of(2026, 10, 20, 8, 0)),
        )

        val sections = HistoryGrouping.group(entries, today, zone)

        assertThat(sections.map { it.day }).containsExactly(
            HistoryDay.Today,
            HistoryDay.Yesterday,
            HistoryDay.Date(LocalDate.of(2026, 10, 20)),
        ).inOrder()
        assertThat(sections[0].entries.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(sections[1].entries.map { it.id }).containsExactly(3L)
        assertThat(sections[2].entries.map { it.id }).containsExactly(4L, 5L).inOrder()
    }

    @Test
    fun `day boundaries follow the given time zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 2026-10-24T20:00Z is already the 25th in Tokyo (UTC+9).
        val entries = listOf(entry(1, LocalDateTime.of(2026, 10, 24, 20, 0)))

        assertThat(HistoryGrouping.group(entries, today, tokyo).single().day).isEqualTo(HistoryDay.Today)
        assertThat(HistoryGrouping.group(entries, today, zone).single().day).isEqualTo(HistoryDay.Yesterday)
    }

    @Test
    fun `section keys are unique per date`() {
        val entries = listOf(
            entry(1, LocalDateTime.of(2026, 10, 25, 9, 0)),
            entry(2, LocalDateTime.of(2026, 9, 1, 9, 0)),
        )
        val keys = HistoryGrouping.group(entries, today, zone).map { it.key }
        assertThat(keys).containsExactly("day-2026-10-25", "day-2026-09-01").inOrder()
    }

    @Test
    fun `future dates are shown as plain dates`() {
        assertThat(HistoryGrouping.dayOf(LocalDate.of(2026, 10, 26), today))
            .isEqualTo(HistoryDay.Date(LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun `empty history has no sections`() {
        assertThat(HistoryGrouping.group(emptyList(), today, zone)).isEmpty()
    }
}
