package app.snapcast.browser.web

/**
 * Whether the top-level load requests of one session were asked for by the user: a tap with a user
 * gesture, or a load the app started itself (`GeckoSession.load`). Gecko reports every HTTP redirect hop
 * as a new load request without the gesture, so a hop inherits the value of the navigation it redirects,
 * as long as that navigation has not ended (committed, been cancelled or failed) and started at most
 * [REDIRECT_WINDOW_MS] ago. Main thread only; one instance per session.
 *
 * @param clock monotonic milliseconds (`SystemClock.elapsedRealtime`)
 */
internal class UserNavigation(private val clock: () -> Long) {
    /** Start time of a navigation the user asked for that is still in flight, or null. */
    private var userNavigationStartedAt: Long? = null

    /**
     * @param opensNewWindow the load targets a new window (target=_blank, window.open): it continues in
     *   another session, so it does not start a navigation of this one
     * @return true when the user asked for this load
     */
    fun onTopLevelLoadRequest(
        hasUserGesture: Boolean,
        isDirectNavigation: Boolean,
        isRedirect: Boolean,
        opensNewWindow: Boolean,
    ): Boolean {
        val now = clock()
        val inherited = isRedirect && userNavigationStartedAt?.let { now - it in 0L..REDIRECT_WINDOW_MS } == true
        val userInitiated = hasUserGesture || isDirectNavigation || inherited
        if (!opensNewWindow && !inherited) userNavigationStartedAt = if (userInitiated) now else null
        return userInitiated
    }

    /**
     * The navigation in flight ended: a location committed, the load was cancelled or failed. Redirect
     * hops reported later belong to a navigation the page started by itself (e.g. a reload).
     */
    fun onNavigationEnded() {
        userNavigationStartedAt = null
    }

    companion object {
        /** Longest time from the user's load request to a redirect hop that still counts as theirs. */
        const val REDIRECT_WINDOW_MS = 20_000L
    }
}
