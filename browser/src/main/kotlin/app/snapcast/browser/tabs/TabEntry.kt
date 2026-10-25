package app.snapcast.browser.tabs

import app.snapcast.browser.TabState
import app.snapcast.data.tabs.SavedTab
import org.mozilla.geckoview.GeckoSession

/**
 * Mutable bookkeeping for one tab, owned by [TabManager] and touched only on the main thread.
 * [state] is the immutable snapshot published to the UI.
 */
internal class TabEntry(
    val id: String,
    var session: GeckoSession,
    val isPrivate: Boolean,
    var state: TabState,
) {
    /** about:blank in this tab means "built-in home page" (false only for fresh engine-created popups). */
    var homeCapable: Boolean = true

    /** The tab left the built-in home page for its current page, so Back can return there. */
    var openedFromHome: Boolean = false

    /** Back/forward as reported by the engine (the published state may add home/parent fallbacks). */
    var sessionCanGoBack: Boolean = false

    /** URL requested from the home page that has not committed yet (reverted if the load is denied). */
    var pendingHomeLoad: String? = null

    /** A new document started loading and its location has not committed yet. */
    var awaitingCommit: Boolean = false

    /** Origin the current [TabState.security] was computed for. */
    var securityOrigin: String? = null

    /** Latest GeckoSession.SessionState JSON, persisted for non-private tabs. */
    var sessionStateJson: String? = null

    /** Saved state not loaded yet: restored tabs load lazily when first selected. */
    var pendingRestore: SavedTab? = null

    val isShowingHome: Boolean get() = state.isHome
}
