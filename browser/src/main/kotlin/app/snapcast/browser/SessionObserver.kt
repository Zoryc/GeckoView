package app.snapcast.browser

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

/**
 * Hooks other modules use to attach behaviour to tabs without the browser module depending on them.
 * Contribute implementations with `@Binds @IntoSet`. All callbacks run on the main thread.
 * Implementations must not inject [BrowserTabs] directly (cycle); inject `dagger.Lazy<BrowserTabs>` instead.
 */
interface SessionObserver {
    /**
     * Session created and opened. Engine-created popup / extension sessions are reported as soon
     * as they are registered, just before GeckoView opens them. Also called with the replacement
     * session after a content crash (preceded by [onSessionClosed] for the crashed one).
     */
    fun onSessionCreated(tabId: String, session: GeckoSession, isPrivate: Boolean) {}

    fun onSessionClosed(tabId: String, session: GeckoSession) {}

    /** A new top-level document started loading (ProgressDelegate.onPageStart). Not called for same-document navigations. */
    fun onPageStart(tabId: String, url: String) {}

    fun onLocationChange(tabId: String, url: String) {}

    fun onTitleChange(tabId: String, title: String) {}

    /** The page asked for Encrypted Media Extensions (PERMISSION_MEDIA_KEY_SYSTEM_ACCESS) => DRM. */
    fun onMediaKeySystemRequested(tabId: String, origin: String) {}

    /** Media session metadata from the page's playing media (MediaSession.Delegate.onMetadata). */
    fun onMediaMetadata(tabId: String, title: String?, artist: String?, album: String?) {}

    fun onSelectedTabChanged(tabId: String?) {}
}

/** Lets other modules consume a navigation before Gecko loads it (e.g. intercept .xpi downloads). */
interface LoadInterceptor {
    /** Return true to block the load because it was handled. Called for top-level and subframe loads. */
    fun interceptLoad(tabId: String, url: String, isTopLevel: Boolean): Boolean

    /**
     * What the browser calls for every top-level and subframe load; the default forwards to the
     * three-argument variant. Override it when a load must only have an effect if the user asked for it.
     *
     * @param userInitiated the user asked for this load: `LoadRequest.hasUserGesture || isDirectNavigation`
     *   (a tap on a link, a typed URL, an app load). A top-level redirect hop inherits the value of the load
     *   it redirects. False for loads a page starts by itself (`location = ...` on load, timers, frames
     *   loading without a tap); subframe loads only report their own gesture.
     */
    fun interceptLoad(tabId: String, url: String, isTopLevel: Boolean, userInitiated: Boolean): Boolean =
        interceptLoad(tabId, url, isTopLevel)
}

/** Lets other modules handle responses Gecko cannot display before they are treated as downloads. */
interface ExternalResponseInterceptor {
    /** Return true when handled; the default download handling is skipped. */
    fun interceptResponse(tabId: String, response: WebResponse): Boolean
}
