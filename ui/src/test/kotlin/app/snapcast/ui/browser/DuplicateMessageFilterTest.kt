package app.snapcast.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DuplicateMessageFilterTest {
    @Test
    fun `first message is shown`() {
        assertThat(DuplicateMessageFilter().shouldShow("Hello", 1_000)).isTrue()
    }

    @Test
    fun `identical message within the window is dropped`() {
        val filter = DuplicateMessageFilter(windowMs = 2_000)
        assertThat(filter.shouldShow("Added to queue: A", 10_000)).isTrue()
        assertThat(filter.shouldShow("Added to queue: A", 11_500)).isFalse()
    }

    @Test
    fun `identical message after the window is shown again`() {
        val filter = DuplicateMessageFilter(windowMs = 2_000)
        assertThat(filter.shouldShow("A", 10_000)).isTrue()
        assertThat(filter.shouldShow("A", 12_001)).isTrue()
    }

    @Test
    fun `different messages are always shown`() {
        val filter = DuplicateMessageFilter()
        assertThat(filter.shouldShow("A", 0)).isTrue()
        assertThat(filter.shouldShow("B", 1)).isTrue()
        assertThat(filter.shouldShow("A", 2)).isTrue()
    }

    @Test
    fun `dropped duplicates do not extend the window`() {
        val filter = DuplicateMessageFilter(windowMs = 1_000)
        assertThat(filter.shouldShow("A", 0)).isTrue()
        assertThat(filter.shouldShow("A", 900)).isFalse()
        assertThat(filter.shouldShow("A", 1_100)).isTrue()
    }
}
