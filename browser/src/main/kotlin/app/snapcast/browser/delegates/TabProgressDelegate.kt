package app.snapcast.browser.delegates

import app.snapcast.browser.SecurityLevel
import app.snapcast.browser.hooks.BrowserHooks
import app.snapcast.browser.tabs.TabHost
import app.snapcast.browser.web.SecurityLevels
import app.snapcast.browser.web.UriParts
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ProgressDelegate.SecurityInformation

/** Loading state, progress, the lock indicator and session-state snapshots for persistence. */
internal class TabProgressDelegate(
    private val host: TabHost,
    private val hooks: BrowserHooks,
) : GeckoSession.ProgressDelegate {

    override fun onPageStart(session: GeckoSession, url: String) {
        val tab = host.tabOf(session) ?: return
        host.onPageStarted(tab, url)
        hooks.notify { it.onPageStart(tab.id, url) }
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        val tab = host.tabOf(session) ?: return
        host.onPageStopped(tab)
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        val tab = host.tabOf(session) ?: return
        if (!tab.state.isLoading) return
        host.update(tab) { it.copy(progress = progress.coerceIn(0, 100)) }
    }

    override fun onSecurityChange(session: GeckoSession, securityInfo: SecurityInformation) {
        val tab = host.tabOf(session)?.takeUnless { it.isDormant } ?: return
        val pageUrl = securityInfo.origin?.takeIf { UriParts.schemeOf(it) != null } ?: tab.state.url
        tab.securityOrigin = UriParts.originOf(pageUrl)
        val level = if (tab.isShowingHome) {
            SecurityLevel.NONE
        } else {
            SecurityLevels.forPage(
                url = pageUrl,
                isSecure = securityInfo.isSecure,
                isException = securityInfo.isException,
                mixedModeActive = securityInfo.mixedModeActive,
                mixedModePassive = securityInfo.mixedModePassive,
            )
        }
        host.update(tab) { it.copy(security = level) }
    }

    override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
        val tab = host.tabOf(session) ?: return
        host.onSessionStateChanged(tab, sessionState.toString())
    }

}
