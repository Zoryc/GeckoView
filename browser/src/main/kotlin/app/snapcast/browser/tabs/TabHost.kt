package app.snapcast.browser.tabs

import app.snapcast.browser.TabState
import org.mozilla.geckoview.GeckoSession

/**
 * What session delegates may ask of the tab manager. Delegates translate engine callbacks; tab
 * state transitions (home page handling, selection, crash recovery, persistence) live behind this
 * interface. Main thread only.
 */
internal interface TabHost {
    /** The tab whose *current* session is [session]; null for unknown or replaced (crashed) sessions. */
    fun tabOf(session: GeckoSession): TabEntry?

    fun isSelected(tab: TabEntry): Boolean

    /** Applies [transform] to the tab's published state; derived fields (canGoBack) are recomputed. */
    fun update(tab: TabEntry, transform: (TabState) -> TabState)

    /** A new top-level document started loading. */
    fun onPageStarted(tab: TabEntry, url: String)

    /** The top-level load finished (successfully or not). */
    fun onPageStopped(tab: TabEntry)

    /** A top-level location committed (handles the built-in home page). */
    fun onLocationCommitted(tab: TabEntry, url: String)

    /** A top-level load produced no page: cancelled by an interceptor, handed to another app, or a download. */
    fun onLoadDenied(tab: TabEntry, url: String)

    fun onSessionStateChanged(tab: TabEntry, json: String?)

    /** Registers a new, unopened child tab for `onNewSession` and returns its session. */
    fun openChildTab(parent: TabEntry): GeckoSession

    fun closeTab(tabId: String)

    /**
     * Replaces a crashed or killed session with a fresh one. The tab reloads now if selected (unless it
     * crashed again within [CrashRecovery.RELOAD_WINDOW_MS]), otherwise when next selected.
     */
    fun recoverSession(tab: TabEntry)
}
