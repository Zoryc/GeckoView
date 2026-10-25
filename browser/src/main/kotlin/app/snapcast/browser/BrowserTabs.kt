package app.snapcast.browser

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.mozilla.geckoview.GeckoSession

/**
 * Tab management: one [GeckoSession] per tab, all on the shared runtime. All methods must be called
 * on the main thread. Implemented by the browser module (bound as a singleton).
 */
interface BrowserTabs {
    /** Open tabs in creation order (normal and private mixed; UI filters by [TabState.isPrivate]). */
    val tabs: StateFlow<List<TabState>>

    val selectedTabId: StateFlow<String?>

    val selectedTab: StateFlow<TabState?>

    /** Pending dialogs, oldest first. */
    val prompts: StateFlow<List<BrowserPrompt>>

    val events: SharedFlow<BrowserEvent>

    /** The open session of a tab, or null for unknown tabs (home page tabs still have a session). */
    fun session(tabId: String): GeckoSession?

    fun tabIdOf(session: GeckoSession): String?

    /**
     * Opens a new tab. [url] null -> the configured home page. Returns the tab id.
     * Private tabs use GeckoSessionSettings.usePrivateMode(true).
     */
    fun newTab(url: String? = null, private: Boolean = false, select: Boolean = true, parentId: String? = null): String

    /**
     * Creates a configured but NOT opened session suitable for returning from GeckoView callbacks that
     * expect a fresh session (NavigationDelegate.onNewSession, WebExtension.TabDelegate.onNewTab), and
     * registers it as a new tab. GeckoView opens it. Returns the tab id and the session.
     */
    fun newTabForEngine(private: Boolean, select: Boolean = true, parentId: String? = null): Pair<String, GeckoSession>

    fun selectTab(tabId: String)

    fun closeTab(tabId: String)

    /** Closes all tabs, or only private / only normal ones. */
    fun closeAllTabs(private: Boolean? = null)

    /** Address-bar entry point: URL or search terms (resolved with the configured search engine). */
    fun loadInput(tabId: String, input: String)

    fun loadUrl(tabId: String, url: String)

    fun goBack(tabId: String)

    fun goForward(tabId: String)

    fun reload(tabId: String, bypassCache: Boolean = false)

    fun stop(tabId: String)

    fun goHome(tabId: String)

    /** Switches user agent + viewport between mobile and desktop and reloads. */
    fun setDesktopMode(tabId: String, enabled: Boolean)

    fun exitFullscreen(tabId: String)

    /** Restores tabs saved from the previous run (or opens one home tab). Called once by the UI on start. */
    fun restoreTabs()

    /** Clears engine data. Flags combine [CLEAR_COOKIES], [CLEAR_CACHE], [CLEAR_SITE_DATA]. */
    suspend fun clearBrowsingData(flags: Int)

    companion object {
        const val CLEAR_COOKIES = 1
        const val CLEAR_CACHE = 2
        const val CLEAR_SITE_DATA = 4
    }
}
