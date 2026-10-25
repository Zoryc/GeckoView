package app.snapcast.addons.tabs

import app.snapcast.browser.BrowserTabs
import javax.inject.Inject
import javax.inject.Singleton
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * The `tabs` API of extensions mapped onto [BrowserTabs]: `tabs.create` / `runtime.openOptionsPage`
 * (extension-wide [WebExtension.TabDelegate]) and `tabs.remove` / `tabs.update` (per-session
 * [WebExtension.SessionTabDelegate]). GeckoView loads the requested URLs itself. Main thread only.
 */
@Singleton
internal class ExtensionTabsDelegate @Inject constructor(
    private val tabs: dagger.Lazy<BrowserTabs>,
) : WebExtension.TabDelegate, WebExtension.SessionTabDelegate {

    /** tabs.create: a new, unopened tab session; GeckoView opens it and loads `createDetails.url`. */
    override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
        // tabs.create defaults to active: true.
        val select = createDetails.active != false
        val (_, session) = tabs.get().newTabForEngine(private = false, select = select)
        return GeckoResult.fromValue(session)
    }

    /** runtime.openOptionsPage() when options_ui.open_in_tab is false: shown in a tab as well. */
    override fun onOpenOptionsPage(source: WebExtension) {
        openOptionsPage(source)
    }

    override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> {
        val browserTabs = tabs.get()
        val tabId = browserTabs.tabIdOf(session) ?: return GeckoResult.deny()
        browserTabs.closeTab(tabId)
        return GeckoResult.allow()
    }

    /** tabs.update: GeckoView navigates by itself; only activation needs the app. */
    override fun onUpdateTab(
        source: WebExtension,
        session: GeckoSession,
        updateDetails: WebExtension.UpdateTabDetails,
    ): GeckoResult<AllowOrDeny> {
        val browserTabs = tabs.get()
        val tabId = browserTabs.tabIdOf(session) ?: return GeckoResult.deny()
        if (updateDetails.active == true) browserTabs.selectTab(tabId)
        return GeckoResult.allow()
    }

    /** Opens (or re-selects) a tab with the extension's options page. Does nothing without one. */
    fun openOptionsPage(extension: WebExtension) {
        val url = extension.metaData.optionsPageUrl?.takeIf { it.isNotBlank() } ?: return
        val browserTabs = tabs.get()
        val existing = browserTabs.tabs.value.firstOrNull { it.url == url && !it.isPrivate }
        if (existing != null) {
            browserTabs.selectTab(existing.id)
        } else {
            browserTabs.newTab(url = url, private = false, select = true)
        }
    }
}
