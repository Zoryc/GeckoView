package app.snapcast.browser.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HistoryVisitsTest {
    // GeckoSession.HistoryDelegate.VISIT_* values from the GeckoView API.
    private val topLevel = 1
    private val redirectTemporary = 2
    private val redirectPermanent = 4
    private val redirectSource = 8
    private val redirectSourcePermanent = 16
    private val unrecoverableError = 32

    @Test
    fun `top-level http visits of normal tabs are recorded`() {
        assertThat(HistoryVisits.shouldRecord("https://example.com", topLevel, isPrivate = false)).isTrue()
        assertThat(HistoryVisits.shouldRecord("http://example.com", topLevel or redirectTemporary, isPrivate = false)).isTrue()
        assertThat(HistoryVisits.shouldRecord("https://example.com", topLevel or redirectPermanent, isPrivate = false)).isTrue()
    }

    @Test
    fun `private, subframe, redirect source and failed visits are skipped`() {
        assertThat(HistoryVisits.shouldRecord("https://example.com", topLevel, isPrivate = true)).isFalse()
        assertThat(HistoryVisits.shouldRecord("https://example.com", 0, isPrivate = false)).isFalse()
        assertThat(HistoryVisits.shouldRecord("https://example.com", topLevel or redirectSource, isPrivate = false)).isFalse()
        assertThat(HistoryVisits.shouldRecord("https://example.com", topLevel or redirectSourcePermanent, isPrivate = false)).isFalse()
        assertThat(HistoryVisits.shouldRecord("https://example.com", topLevel or unrecoverableError, isPrivate = false)).isFalse()
    }

    @Test
    fun `non-web pages are skipped`() {
        assertThat(HistoryVisits.shouldRecord("about:blank", topLevel, isPrivate = false)).isFalse()
        assertThat(HistoryVisits.shouldRecord("moz-extension://x/options.html", topLevel, isPrivate = false)).isFalse()
        assertThat(HistoryVisits.shouldRecord("data:text/html,x", topLevel, isPrivate = false)).isFalse()
    }
}
