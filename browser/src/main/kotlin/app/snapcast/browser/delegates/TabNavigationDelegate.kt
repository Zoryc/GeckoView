package app.snapcast.browser.delegates

import app.snapcast.browser.BrowserEvent
import app.snapcast.browser.hooks.BrowserEventBus
import app.snapcast.browser.hooks.BrowserHooks
import app.snapcast.browser.tabs.TabHost
import app.snapcast.browser.web.ErrorPageRenderer
import app.snapcast.browser.web.ExternalSchemes
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.WebRequestError

/** Location, history availability, load filtering (hooks + app links), popups and error pages. */
internal class TabNavigationDelegate(
    private val host: TabHost,
    private val hooks: BrowserHooks,
    private val events: BrowserEventBus,
    private val errorPages: ErrorPageRenderer,
) : GeckoSession.NavigationDelegate {

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<ContentPermission>,
        hasUserGesture: Boolean,
    ) {
        val tab = host.tabOf(session) ?: return
        val location = url ?: return
        host.onLocationCommitted(tab, location)
        hooks.notify { it.onLocationChange(tab.id, location) }
    }

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        val tab = host.tabOf(session)?.takeUnless { it.isDormant } ?: return
        tab.sessionCanGoBack = canGoBack
        host.update(tab) { it }
    }

    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        val tab = host.tabOf(session)?.takeUnless { it.isDormant } ?: return
        host.update(tab) { it.copy(canGoForward = canGoForward) }
    }

    override fun onLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny>? {
        val tab = host.tabOf(session) ?: return GeckoResult.allow()
        val uri = request.uri
        if (hooks.interceptLoad(tab.id, uri, isTopLevel = true)) {
            host.onLoadDenied(tab, uri)
            return GeckoResult.deny()
        }
        return when (ExternalSchemes.decide(uri, request.hasUserGesture, request.isDirectNavigation)) {
            ExternalSchemes.Decision.ENGINE -> GeckoResult.allow()
            ExternalSchemes.Decision.OPEN_EXTERNALLY -> {
                events.emit(BrowserEvent.OpenExternal(uri))
                host.onLoadDenied(tab, uri)
                GeckoResult.deny()
            }
            ExternalSchemes.Decision.BLOCK -> {
                host.onLoadDenied(tab, uri)
                GeckoResult.deny()
            }
        }
    }

    override fun onSubframeLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny>? {
        val tab = host.tabOf(session) ?: return GeckoResult.allow()
        return if (hooks.interceptLoad(tab.id, request.uri, isTopLevel = false)) GeckoResult.deny() else GeckoResult.allow()
    }

    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
        val parent = host.tabOf(session) ?: return null
        return GeckoResult.fromValue(host.openChildTab(parent))
    }

    override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? =
        GeckoResult.fromValue(errorPages.render(uri, error.category, error.code))
}
