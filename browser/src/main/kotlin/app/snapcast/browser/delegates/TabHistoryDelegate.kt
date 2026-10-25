package app.snapcast.browser.delegates

import app.snapcast.browser.tabs.TabHost
import app.snapcast.browser.web.HistoryVisits
import app.snapcast.data.history.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * Records top-level visits of normal tabs in [HistoryRepository]. `getVisited` keeps its default
 * (null), so `:visited` link styling is not tracked.
 */
internal class TabHistoryDelegate(
    private val host: TabHost,
    private val history: HistoryRepository,
    private val appScope: CoroutineScope,
) : GeckoSession.HistoryDelegate {

    override fun onVisited(session: GeckoSession, url: String, lastVisitedURL: String?, flags: Int): GeckoResult<Boolean>? {
        val tab = host.tabOf(session)
        if (tab == null || !HistoryVisits.shouldRecord(url, flags, tab.isPrivate)) return GeckoResult.fromValue(false)
        val title = tab.state.title.takeIf { tab.state.url == url && it.isNotBlank() }
        appScope.launch(Dispatchers.IO) { history.recordVisit(url, title) }
        return GeckoResult.fromValue(true)
    }
}
