package app.snapcast.ui.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.browser.BrowserTabs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Tab switcher state. The switcher opens on the mode (normal / private) of the selected tab; all calls
 * reach [BrowserTabs] on the main thread (they come straight from the UI).
 */
@HiltViewModel
internal class TabsViewModel @Inject constructor(
    private val browserTabs: BrowserTabs,
) : ViewModel() {

    private val showPrivate = MutableStateFlow(browserTabs.selectedTab.value?.isPrivate == true)

    val uiState: StateFlow<TabsUiState> =
        combine(browserTabs.tabs, browserTabs.selectedTabId, showPrivate) { tabs, selectedId, private ->
            TabCards.state(tabs, selectedId, private)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = TabCards.state(
                browserTabs.tabs.value,
                browserTabs.selectedTabId.value,
                showPrivate.value,
            ),
        )

    fun showPrivateTabs(private: Boolean) {
        showPrivate.value = private
    }

    fun selectTab(tabId: String) = browserTabs.selectTab(tabId)

    fun closeTab(tabId: String) = browserTabs.closeTab(tabId)

    /** Opens and selects a new tab of the mode currently shown. */
    fun newTab() {
        browserTabs.newTab(url = null, private = showPrivate.value, select = true)
    }

    /** Closes every tab of the mode currently shown. */
    fun closeAllTabs() = browserTabs.closeAllTabs(private = showPrivate.value)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
