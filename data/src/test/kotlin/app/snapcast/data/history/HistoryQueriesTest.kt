package app.snapcast.data.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HistoryQueriesTest {
    @Test
    fun escapeLikeEscapesWildcardsAndEscapeCharacter() {
        assertThat(HistoryQueries.escapeLike("plain text")).isEqualTo("plain text")
        assertThat(HistoryQueries.escapeLike("100%")).isEqualTo("100\\%")
        assertThat(HistoryQueries.escapeLike("a_b")).isEqualTo("a\\_b")
        assertThat(HistoryQueries.escapeLike("c:\\dir")).isEqualTo("c:\\\\dir")
    }

    @Test
    fun containsPatternWrapsEscapedText() {
        assertThat(HistoryQueries.containsPattern("vimeo")).isEqualTo("%vimeo%")
        assertThat(HistoryQueries.containsPattern("50%_off")).isEqualTo("%50\\%\\_off%")
    }

    @Test
    fun webAndFileUrlsAreRecordable() {
        assertThat(HistoryQueries.isRecordable("https://vimeo.com/")).isTrue()
        assertThat(HistoryQueries.isRecordable("http://example.com/a?b=c")).isTrue()
        assertThat(HistoryQueries.isRecordable("file:///sdcard/video.html")).isTrue()
    }

    @Test
    fun internalPagesAreNotRecordable() {
        listOf(
            "",
            "   ",
            "about:blank",
            "ABOUT:config",
            "snapcast:home",
            "data:text/html,hi",
            "blob:https://example.com/123",
            "javascript:void(0)",
            "moz-extension://abc/popup.html",
            "resource://android/assets/x",
            "view-source:https://example.com/",
            "no-scheme-here",
        ).forEach { url -> assertThat(HistoryQueries.isRecordable(url)).isFalse() }
    }

    @Test
    fun cleanTitleTrimsAndDropsBlankTitles() {
        assertThat(HistoryQueries.cleanTitle("  Title  ")).isEqualTo("Title")
        assertThat(HistoryQueries.cleanTitle("   ")).isNull()
        assertThat(HistoryQueries.cleanTitle(null)).isNull()
    }
}
