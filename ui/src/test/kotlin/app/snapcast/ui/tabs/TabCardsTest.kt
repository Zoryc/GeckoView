package app.snapcast.ui.tabs

import app.snapcast.browser.TabState
import app.snapcast.core.model.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TabCardsTest {
    private val home = TabState(id = "h", url = AppSettings.HOME_PAGE_BUILT_IN, title = "", isPrivate = false)
    private val page = TabState(
        id = "p",
        url = "https://www.example.com/watch?v=1",
        title = "  Great video ",
        isPrivate = false,
        isLoading = true,
        progress = 140,
        isMediaPlaying = true,
    )
    private val untitled = TabState(id = "u", url = "https://news.example.org/", title = "", isPrivate = true)

    @Test
    fun `splits normal and private tabs and marks the selected one`() {
        val state = TabCards.state(listOf(home, untitled, page), selectedTabId = "p", showPrivate = false)
        assertThat(state.normalTabs.map { it.id }).containsExactly("h", "p").inOrder()
        assertThat(state.privateTabs.map { it.id }).containsExactly("u")
        assertThat(state.visibleTabs).isEqualTo(state.normalTabs)
        assertThat(state.normalTabs.single { it.isSelected }.id).isEqualTo("p")
        assertThat(state.copy(showPrivate = true).visibleTabs).isEqualTo(state.privateTabs)
    }

    @Test
    fun `card titles fall back to the host`() {
        val card = TabCards.card(page, selectedTabId = null)
        assertThat(card.title).isEqualTo("Great video")
        assertThat(card.host).isEqualTo("example.com")
        assertThat(card.progress).isEqualTo(100)
        assertThat(card.isLoading).isTrue()
        assertThat(card.isMediaPlaying).isTrue()
        assertThat(TabCards.card(untitled, null).title).isEqualTo("news.example.org")
    }

    @Test
    fun `home and internal pages`() {
        val card = TabCards.card(home, selectedTabId = "h")
        assertThat(card.isHome).isTrue()
        assertThat(card.title).isEmpty()
        assertThat(card.host).isEmpty()
        assertThat(TabCards.displayHost("about:addons")).isEqualTo("about:addons")
    }
}
