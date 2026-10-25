package app.snapcast.ui.bookmarks

import app.snapcast.data.bookmarks.Bookmark
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookmarkOrderingTest {
    private fun bookmark(id: Long, position: Int) =
        Bookmark(id = id, url = "https://example.com/$id", title = "B$id", createdAt = 0, position = position, onHome = false)

    @Test
    fun `moving up and down reorders the list`() {
        val list = listOf(bookmark(1, 0), bookmark(2, 1), bookmark(3, 2))
        assertThat(BookmarkOrdering.move(list, id = 3, delta = -1)!!.map { it.id }).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(BookmarkOrdering.move(list, id = 1, delta = 1)!!.map { it.id }).containsExactly(2L, 1L, 3L).inOrder()
    }

    @Test
    fun `moving past the ends or unknown ids does nothing`() {
        val list = listOf(bookmark(1, 0), bookmark(2, 1))
        assertThat(BookmarkOrdering.move(list, id = 1, delta = -1)).isNull()
        assertThat(BookmarkOrdering.move(list, id = 2, delta = 1)).isNull()
        assertThat(BookmarkOrdering.move(list, id = 9, delta = 1)).isNull()
    }

    @Test
    fun `renumber only touches bookmarks whose position changes`() {
        // Seeded speed dial entries can share position 0.
        val ordered = listOf(bookmark(2, 0), bookmark(1, 0), bookmark(3, 2))
        val changed = BookmarkOrdering.renumber(ordered)
        assertThat(changed.map { it.id to it.position }).containsExactly(1L to 1)
    }

    @Test
    fun `bookmark urls are normalized or rejected`() {
        assertThat(BookmarkInput.normalizeUrl("example.com/videos")).isEqualTo("https://example.com/videos")
        assertThat(BookmarkInput.normalizeUrl("  https://a.example.org  ")).isEqualTo("https://a.example.org")
        assertThat(BookmarkInput.normalizeUrl("funny cat videos")).isNull()
        assertThat(BookmarkInput.normalizeUrl("")).isNull()
    }
}
