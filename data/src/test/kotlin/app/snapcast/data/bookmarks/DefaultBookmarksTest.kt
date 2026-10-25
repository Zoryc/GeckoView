package app.snapcast.data.bookmarks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultBookmarksTest {
    @Test
    fun defaultSpeedDialSitesInOrder() {
        assertThat(DefaultBookmarks.ALL.map { it.url }).containsExactly(
            "https://archive.org/details/movies",
            "https://vimeo.com/",
            "https://www.dailymotion.com/",
            "https://plus.nasa.gov/",
            "https://commons.wikimedia.org/wiki/Category:Videos",
            "https://developer.apple.com/streaming/examples/",
        ).inOrder()
    }

    @Test
    fun defaultsHaveTitlesAndDistinctHttpsUrls() {
        val all = DefaultBookmarks.ALL
        assertThat(all.map { it.url }.toSet()).hasSize(all.size)
        all.forEach {
            assertThat(it.url).startsWith("https://")
            assertThat(it.title).isNotEmpty()
        }
    }

    @Test
    fun displayTitleFallsBackToUrl() {
        assertThat(DefaultBookmarks.displayTitle("https://a.test/", "  A  ")).isEqualTo("A")
        assertThat(DefaultBookmarks.displayTitle("https://a.test/", "  ")).isEqualTo("https://a.test/")
    }
}
