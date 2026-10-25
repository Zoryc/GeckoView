package app.snapcast.ui.tabs

import app.snapcast.browser.TabState
import app.snapcast.core.util.UrlUtils

/** What one card of the tab switcher shows. */
internal data class TabCardModel(
    val id: String,
    /** Page title, or the host / URL when the page has none; blank for the built-in home page. */
    val title: String,
    /** Host without "www." for web pages, the full URL for other schemes, blank for the home page. */
    val host: String,
    val url: String,
    val isHome: Boolean,
    val isSelected: Boolean,
    val isLoading: Boolean,
    /** 0..100 */
    val progress: Int,
    val isMediaPlaying: Boolean,
)

/** Normal and private tabs of the switcher, in creation order. */
internal data class TabsUiState(
    val showPrivate: Boolean = false,
    val normalTabs: List<TabCardModel> = emptyList(),
    val privateTabs: List<TabCardModel> = emptyList(),
) {
    val visibleTabs: List<TabCardModel> get() = if (showPrivate) privateTabs else normalTabs
}

/** Pure mapping from the browser's [TabState]s to switcher cards. */
internal object TabCards {
    fun state(tabs: List<TabState>, selectedTabId: String?, showPrivate: Boolean): TabsUiState {
        val (privateTabs, normalTabs) = tabs.partition { it.isPrivate }
        return TabsUiState(
            showPrivate = showPrivate,
            normalTabs = normalTabs.map { card(it, selectedTabId) },
            privateTabs = privateTabs.map { card(it, selectedTabId) },
        )
    }

    fun card(tab: TabState, selectedTabId: String?): TabCardModel {
        val host = if (tab.isHome) "" else displayHost(tab.url)
        val title = when {
            tab.isHome -> ""
            tab.title.isNotBlank() -> tab.title.trim()
            host.isNotBlank() -> host
            else -> tab.url
        }
        return TabCardModel(
            id = tab.id,
            title = title,
            host = host,
            url = tab.url,
            isHome = tab.isHome,
            isSelected = tab.id == selectedTabId,
            isLoading = tab.isLoading,
            progress = tab.progress.coerceIn(0, 100),
            isMediaPlaying = tab.isMediaPlaying,
        )
    }

    /** "example.com" for http(s) URLs (without "www."), otherwise the URL itself (about:, moz-extension:...). */
    fun displayHost(url: String): String = UrlUtils.hostOf(url)?.removePrefix("www.") ?: url
}
