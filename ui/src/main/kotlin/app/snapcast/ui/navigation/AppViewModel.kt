package app.snapcast.ui.navigation

import androidx.lifecycle.ViewModel
import app.snapcast.browser.BrowserTabs
import app.snapcast.core.model.AppSettings
import app.snapcast.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Activity-scoped state of the app shell: theme settings, tab restore and URL routing. */
@HiltViewModel
internal class AppViewModel @Inject constructor(
    private val browserTabs: BrowserTabs,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    init {
        // BrowserTabs is a process singleton: restore saved tabs once per process, not once per activity.
        if (tabsRestored.compareAndSet(false, true)) browserTabs.restoreTabs()
    }

    /** ExternalRequest.OpenUrl: a new (optionally private) tab showing [url]. */
    fun openInNewTab(url: String, private: Boolean) {
        browserTabs.newTab(url = url, private = private, select = true)
    }

    /** "Open" from history/bookmarks/add-ons/local media: load in the selected tab (or a new one). */
    fun openInSelectedTab(url: String) {
        val tabId = browserTabs.selectedTabId.value
        if (tabId != null && browserTabs.session(tabId) != null) {
            browserTabs.loadUrl(tabId, url)
        } else {
            browserTabs.newTab(url = url)
        }
    }

    private companion object {
        val tabsRestored = AtomicBoolean(false)
    }
}
