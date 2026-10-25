package app.snapcast.browser.delegates

import android.content.Context
import app.snapcast.browser.download.DownloadQueue
import app.snapcast.browser.hooks.BrowserEventBus
import app.snapcast.browser.hooks.BrowserHooks
import app.snapcast.browser.prompts.PromptRegistry
import app.snapcast.browser.tabs.TabHost
import app.snapcast.browser.web.ErrorPageRenderer
import app.snapcast.data.di.ApplicationScope
import app.snapcast.data.history.HistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import org.mozilla.geckoview.GeckoSession

/**
 * Installs one fresh set of delegates (navigation, progress, content, prompt, permission, media
 * session, history) on a session. Delegates set before `open()` are kept by GeckoView, so this
 * also works for engine-created sessions that GeckoView opens itself.
 */
@Singleton
internal class SessionDelegates @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hooks: BrowserHooks,
    private val events: BrowserEventBus,
    private val prompts: PromptRegistry,
    private val downloads: DownloadQueue,
    private val history: HistoryRepository,
    private val errorPages: ErrorPageRenderer,
    private val uploads: UploadFileCopier,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun attach(session: GeckoSession, host: TabHost) {
        session.navigationDelegate = TabNavigationDelegate(host, hooks, events, errorPages)
        session.progressDelegate = TabProgressDelegate(host, hooks)
        session.contentDelegate = TabContentDelegate(host, hooks, events, downloads, history, appScope)
        session.promptDelegate = TabPromptDelegate(context, host, prompts, uploads, appScope)
        session.permissionDelegate = TabPermissionDelegate(context, host, hooks, prompts)
        session.mediaSessionDelegate = TabMediaSessionDelegate(host, hooks)
        session.historyDelegate = TabHistoryDelegate(host, history, appScope)
    }

    /** Removes the delegates so a closed or replaced session cannot call back into its tab. */
    fun detach(session: GeckoSession) {
        session.navigationDelegate = null
        session.progressDelegate = null
        session.contentDelegate = null
        session.promptDelegate = null
        session.permissionDelegate = null
        session.mediaSessionDelegate = null
        session.historyDelegate = null
    }
}
