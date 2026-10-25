package app.snapcast.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SuggestionMergerTest {
    private val bookmarks = listOf(
        "https://www.youtube.com/" to "YouTube",
        "https://vimeo.com/" to "Vimeo",
        "https://example.org/videos" to "Cat videos",
    )

    @Test
    fun `blank query gives nothing`() {
        assertThat(SuggestionMerger.merge("  ", bookmarks, listOf("https://a.com" to "A"))).isEmpty()
    }

    @Test
    fun `matching bookmarks come before history`() {
        val history = listOf("https://news.example.com/video" to "Video news")
        val result = SuggestionMerger.merge("vi", bookmarks, history)
        assertThat(result.map { it.url }).containsExactly(
            "https://vimeo.com/",
            "https://example.org/videos",
            "https://news.example.com/video",
        ).inOrder()
        assertThat(result.take(2).all { it.isBookmark }).isTrue()
        assertThat(result.last().isBookmark).isFalse()
    }

    @Test
    fun `bookmark matching is case-insensitive on title and url`() {
        val result = SuggestionMerger.merge("YOUTUBE", bookmarks, emptyList())
        assertThat(result.map { it.title }).containsExactly("YouTube")
    }

    @Test
    fun `history duplicates of bookmarks are dropped ignoring trailing slash and host case`() {
        val history = listOf(
            "https://VIMEO.com" to "Vimeo (history)",
            "https://vimeo.com/123" to "A clip",
        )
        val result = SuggestionMerger.merge("vimeo", bookmarks, history)
        assertThat(result.map { it.url }).containsExactly("https://vimeo.com/", "https://vimeo.com/123").inOrder()
    }

    @Test
    fun `result is limited`() {
        val history = (1..20).map { "https://site$it.com/" to "Site $it" }
        assertThat(SuggestionMerger.merge("site", emptyList(), history, limit = 5)).hasSize(5)
    }

    @Test
    fun `blank titles fall back to the url`() {
        val result = SuggestionMerger.merge("abc", emptyList(), listOf("https://abc.com/" to ""))
        assertThat(result.single().title).isEqualTo("https://abc.com/")
    }

    @Test
    fun `dedupe key keeps path case`() {
        assertThat(SuggestionMerger.dedupeKey("HTTPS://Example.COM/Path/"))
            .isEqualTo("https://example.com/Path")
    }
}
