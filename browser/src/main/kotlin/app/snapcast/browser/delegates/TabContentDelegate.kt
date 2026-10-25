package app.snapcast.browser.delegates

import app.snapcast.browser.BrowserEvent
import app.snapcast.browser.download.DownloadQueue
import app.snapcast.browser.hooks.BrowserEventBus
import app.snapcast.browser.hooks.BrowserHooks
import app.snapcast.browser.tabs.TabHost
import app.snapcast.core.util.UrlUtils
import app.snapcast.data.history.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import org.mozilla.geckoview.WebResponse

/** Titles, fullscreen, context menus, crashes, window.close() and downloads. */
internal class TabContentDelegate(
    private val host: TabHost,
    private val hooks: BrowserHooks,
    private val events: BrowserEventBus,
    private val downloads: DownloadQueue,
    private val history: HistoryRepository,
    private val appScope: CoroutineScope,
) : GeckoSession.ContentDelegate {

    override fun onTitleChange(session: GeckoSession, title: String?) {
        val tab = host.tabOf(session) ?: return
        if (tab.isShowingHome || tab.isDormant) return
        val text = title.orEmpty()
        host.update(tab) { it.copy(title = text) }
        if (title == null) return
        hooks.notify { it.onTitleChange(tab.id, title) }
        val url = tab.state.url
        if (!tab.isPrivate && title.isNotBlank() && UrlUtils.isHttp(url)) {
            appScope.launch(Dispatchers.IO) { history.updateTitle(url, title) }
        }
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        val tab = host.tabOf(session) ?: return
        host.update(tab) { it.copy(isFullscreen = fullScreen) }
    }

    override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContextElement) {
        val tab = host.tabOf(session) ?: return
        val type = when (element.type) {
            ContextElement.TYPE_IMAGE -> BrowserEvent.ContextElementType.IMAGE
            ContextElement.TYPE_VIDEO -> BrowserEvent.ContextElementType.VIDEO
            ContextElement.TYPE_AUDIO -> BrowserEvent.ContextElementType.AUDIO
            else -> BrowserEvent.ContextElementType.NONE
        }
        // Long-presses on plain text are handled by text selection, not by a menu.
        if (element.linkUri == null && element.srcUri == null) return
        events.emit(
            BrowserEvent.ContextMenu(
                tabId = tab.id,
                linkUrl = element.linkUri,
                srcUrl = element.srcUri,
                type = type,
                title = element.title ?: element.linkText,
                altText = element.altText,
            ),
        )
    }

    override fun onCrash(session: GeckoSession) {
        recover(session)
    }

    override fun onKill(session: GeckoSession) {
        recover(session)
    }

    private fun recover(session: GeckoSession) {
        val tab = host.tabOf(session) ?: return
        events.emit(BrowserEvent.TabCrashed(tab.id))
        host.recoverSession(tab)
    }

    override fun onCloseRequest(session: GeckoSession) {
        val tab = host.tabOf(session) ?: return
        host.closeTab(tab.id)
    }

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        val tab = host.tabOf(session)
        if (tab != null && hooks.interceptResponse(tab.id, response)) {
            host.onLoadDenied(tab, response.uri)
            return
        }
        downloads.enqueue(response, isPrivate = tab?.isPrivate ?: false)
        if (tab == null) return
        // Nothing replaces the page: go back to the home page if the download started from there.
        host.onLoadDenied(tab, response.uri)
        // A link opened in a new tab that turned out to be a download leaves an empty tab behind.
        if (tab.state.parentId != null && !tab.sessionCanGoBack && tab.state.url == ABOUT_BLANK) {
            host.closeTab(tab.id)
        }
    }

    private companion object {
        const val ABOUT_BLANK = "about:blank"
    }
}
