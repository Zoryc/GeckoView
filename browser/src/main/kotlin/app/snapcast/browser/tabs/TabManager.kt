package app.snapcast.browser.tabs

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.snapcast.browser.BrowserEvent
import app.snapcast.browser.BrowserPrompt
import app.snapcast.browser.BrowserTabs
import app.snapcast.browser.GeckoRuntimeProvider
import app.snapcast.browser.R
import app.snapcast.browser.SecurityLevel
import app.snapcast.browser.TabState
import app.snapcast.browser.delegates.SessionDelegates
import app.snapcast.browser.hooks.BrowserEventBus
import app.snapcast.browser.hooks.BrowserHooks
import app.snapcast.browser.prompts.PromptRegistry
import app.snapcast.browser.util.await
import app.snapcast.browser.web.ClearDataFlags
import app.snapcast.browser.web.ExternalSchemes
import app.snapcast.browser.web.SecurityLevels
import app.snapcast.browser.web.UriParts
import app.snapcast.core.browser.UrlInputResolver
import app.snapcast.core.model.AppSettings
import app.snapcast.data.di.ApplicationScope
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.data.tabs.SavedTab
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * [BrowserTabs] implementation: one [GeckoSession] per tab on the shared runtime.
 *
 * The built-in home page is an about:blank document in a "home-capable" tab, published as
 * [AppSettings.HOME_PAGE_BUILT_IN]; it is a real history entry, so Back from the first page
 * returns to it. Tabs restored from the previous run only load when first selected.
 * Main thread only.
 */
@Singleton
internal class TabManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtimeProvider: GeckoRuntimeProvider,
    private val settingsRepository: SettingsRepository,
    private val persistence: TabPersistence,
    private val delegates: SessionDelegates,
    private val hooks: BrowserHooks,
    private val promptRegistry: PromptRegistry,
    private val eventBus: BrowserEventBus,
    @ApplicationScope private val appScope: CoroutineScope,
) : BrowserTabs, TabHost {

    private val entries = LinkedHashMap<String, TabEntry>()
    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    private val _selectedTabId = MutableStateFlow<String?>(null)
    private val _selectedTab = MutableStateFlow<TabState?>(null)

    override val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()
    override val selectedTabId: StateFlow<String?> = _selectedTabId.asStateFlow()
    override val selectedTab: StateFlow<TabState?> = _selectedTab.asStateFlow()
    override val prompts: StateFlow<List<BrowserPrompt>> = promptRegistry.prompts
    override val events: SharedFlow<BrowserEvent> = eventBus.events

    private var restoreRequested = false

    /** Saving starts once the previous run's tabs are back, so they are never overwritten early. */
    private var restored = false

    private val runtime: GeckoRuntime get() = runtimeProvider.runtime
    private val settings: AppSettings get() = settingsRepository.settings.value
    private val homeTitle: String get() = context.getString(R.string.browser_home_title)

    // region BrowserTabs

    override fun session(tabId: String): GeckoSession? = entries[tabId]?.session

    override fun tabIdOf(session: GeckoSession): String? = tabOf(session)?.id

    override fun newTab(url: String?, private: Boolean, select: Boolean, parentId: String?): String {
        val entry = createEntry(UUID.randomUUID().toString(), private, desktopModeFor(parentId), parentId)
        openAndRegister(entry)
        if (url == null) {
            goHome(entry)
        } else {
            // A fresh tab behaves like an empty home page until the page commits, so a denied
            // load (app link, intercepted download) falls back to the home page.
            showHomeState(entry)
            load(entry, url, backReturnsHome = false)
        }
        if (select) selectTab(entry.id) else entry.session.setActive(false)
        publish()
        return entry.id
    }

    override fun newTabForEngine(private: Boolean, select: Boolean, parentId: String?): Pair<String, GeckoSession> {
        val entry = createEntry(UUID.randomUUID().toString(), private, desktopModeFor(parentId), parentId)
        // A popup starts at about:blank before navigating; that is not the home page.
        entry.homeCapable = false
        delegates.attach(entry.session, this)
        register(entry)
        // GeckoView opens the session right after it is returned; delegates set now are kept.
        hooks.notify { it.onSessionCreated(entry.id, entry.session, entry.isPrivate) }
        if (select) selectTab(entry.id) else entry.session.setActive(false)
        publish()
        return entry.id to entry.session
    }

    override fun selectTab(tabId: String) {
        val next = entries[tabId] ?: return
        val previousId = _selectedTabId.value
        next.state = next.state.copy(lastAccessed = System.currentTimeMillis())
        if (previousId == tabId) {
            publish()
            return
        }
        previousId?.let { entries[it] }?.let(::deactivate)
        activate(next)
        _selectedTabId.value = tabId
        next.pendingRestore?.let { applyRestore(next, it) }
        publish()
        hooks.notify { it.onSelectedTabChanged(tabId) }
    }

    override fun closeTab(tabId: String) {
        val entry = entries[tabId] ?: return
        val wasSelected = _selectedTabId.value == tabId
        val next = if (wasSelected) TabSelection.afterClose(candidates(), tabId, entry.state.parentId) else null
        if (wasSelected) _selectedTabId.value = null
        removeEntry(entry)
        when {
            next != null -> selectTab(next)
            wasSelected && entry.isPrivate -> {
                val normal = TabSelection.mostRecent(candidates(), private = false)
                if (normal != null) selectTab(normal) else newTab(url = null, private = false, select = true)
            }
            wasSelected -> newTab(url = null, private = false, select = true)
            !entry.isPrivate && entries.values.none { !it.isPrivate } -> newTab(url = null, private = false, select = false)
        }
        publish()
    }

    override fun closeAllTabs(private: Boolean?) {
        val targets = entries.values.filter { private == null || it.isPrivate == private }
        if (targets.isEmpty()) return
        val selectedClosed = targets.any { it.id == _selectedTabId.value }
        if (selectedClosed) _selectedTabId.value = null
        targets.forEach(::removeEntry)
        if (entries.values.none { !it.isPrivate }) {
            newTab(url = null, private = false, select = _selectedTabId.value == null)
        } else if (selectedClosed) {
            TabSelection.mostRecent(candidates(), private = false)?.let(::selectTab)
        }
        publish()
    }

    override fun loadInput(tabId: String, input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = if (text == AppSettings.HOME_PAGE_BUILT_IN) text else UrlInputResolver.resolve(text, settings.searchEngine)
        loadUrl(tabId, url)
    }

    override fun loadUrl(tabId: String, url: String) {
        entries[tabId]?.let { load(it, url) }
    }

    override fun goBack(tabId: String) {
        val entry = entries[tabId] ?: return
        val parentId = entry.state.parentId
        when {
            entry.pendingRestore != null -> Unit
            entry.sessionCanGoBack -> entry.session.goBack()
            // The page is the first history entry: the home page replaces it, so Back on the home
            // page cannot return to it (which would otherwise alternate between the two forever).
            entry.openedFromHome && !entry.isShowingHome -> showHome(entry, replaceHistory = true)
            parentId != null && entries.containsKey(parentId) -> closeTab(tabId)
        }
    }

    override fun goForward(tabId: String) {
        val entry = entries[tabId] ?: return
        if (entry.state.canGoForward) entry.session.goForward()
    }

    override fun reload(tabId: String, bypassCache: Boolean) {
        val entry = entries[tabId] ?: return
        if (entry.isShowingHome) return
        val pending = entry.pendingRestore
        if (pending != null) {
            applyRestore(entry, pending)
            return
        }
        entry.session.reload(if (bypassCache) GeckoSession.LOAD_FLAGS_BYPASS_CACHE else GeckoSession.LOAD_FLAGS_NONE)
    }

    override fun stop(tabId: String) {
        val entry = entries[tabId] ?: return
        entry.session.stop()
        entry.pendingHomeLoad?.let { onLoadDenied(entry, it) }
    }

    override fun goHome(tabId: String) {
        entries[tabId]?.let { goHome(it) }
    }

    override fun setDesktopMode(tabId: String, enabled: Boolean) {
        val entry = entries[tabId] ?: return
        if (entry.state.isDesktopMode == enabled) return
        applyDesktopMode(entry.session.settings, enabled)
        update(entry) { it.copy(isDesktopMode = enabled) }
        if (!entry.isShowingHome && entry.pendingRestore == null) entry.session.reload()
    }

    override fun exitFullscreen(tabId: String) {
        entries[tabId]?.session?.exitFullScreen()
    }

    override fun restoreTabs() {
        if (restoreRequested) return
        restoreRequested = true
        installRuntimeHooks()
        appScope.launch(Dispatchers.Main.immediate) {
            val saved = persistence.load()
            restoreSaved(saved)
            restored = true
            scheduleSave()
        }
    }

    override suspend fun clearBrowsingData(flags: Int) {
        val geckoFlags = ClearDataFlags.toGecko(flags)
        if (geckoFlags == 0L) return
        withContext(Dispatchers.Main.immediate) {
            runtime.storageController.clearData(geckoFlags).await()
        }
    }

    // endregion

    // region TabHost

    override fun tabOf(session: GeckoSession): TabEntry? = entries.values.firstOrNull { it.session === session }

    override fun isSelected(tab: TabEntry): Boolean = _selectedTabId.value == tab.id

    override fun update(tab: TabEntry, transform: (TabState) -> TabState) {
        if (entries[tab.id] !== tab) return
        val next = withDerivedState(tab, transform(tab.state))
        if (next == tab.state) return
        tab.state = next
        publish()
    }

    override fun onPageStarted(tab: TabEntry, url: String) {
        // The built-in home page is an about:blank document under the home UI: no progress bar.
        if (tab.isDormant || (tab.homeCapable && url == ABOUT_BLANK)) return
        tab.awaitingCommit = true
        update(tab) { it.copy(isLoading = true, progress = INITIAL_PROGRESS) }
    }

    override fun onPageStopped(tab: TabEntry) {
        val pending = tab.pendingHomeLoad
        if (pending != null && tab.awaitingCommit) {
            // The page requested from home finished without committing (e.g. it was a download).
            tab.awaitingCommit = false
            onLoadDenied(tab, pending)
        }
        update(tab) { it.copy(isLoading = false, progress = if (it.isLoading) 100 else it.progress) }
    }

    override fun onLocationCommitted(tab: TabEntry, url: String) {
        if (tab.isDormant) return
        if (url == ABOUT_BLANK && tab.homeCapable) {
            // Either the home entry itself (also when navigating back to it) or the old home
            // document while a page requested from home is still loading.
            if (tab.pendingHomeLoad == null && !tab.isShowingHome) {
                tab.openedFromHome = false
                showHomeState(tab)
            }
            return
        }
        // A page replaced the home page (also an extension's tabs.update, which GeckoView navigates
        // by itself): Back returns home. Back from the tab's first page replaces that page with the
        // home entry (see goBack), so this cannot bounce between the two.
        if (tab.isShowingHome) tab.openedFromHome = true
        tab.pendingHomeLoad = null
        val newDocument = tab.awaitingCommit
        tab.awaitingCommit = false
        val origin = UriParts.originOf(url)
        if (origin != tab.committedOrigin) {
            // Permission requests of the previous site are moot now.
            promptRegistry.dismissPermissionPrompts(tab.id)
            tab.committedOrigin = origin
        }
        val keepSecurity = origin != null && origin == tab.securityOrigin
        if (!keepSecurity) tab.securityOrigin = null
        update(tab) { state ->
            state.copy(
                url = url,
                title = if (newDocument || state.isHome) "" else state.title,
                security = if (keepSecurity) state.security else SecurityLevels.provisional(url),
            )
        }
    }

    override fun onLoadDenied(tab: TabEntry, url: String) {
        if (tab.pendingHomeLoad == null) return
        // Nothing replaced the home page: show it again instead of an empty page.
        tab.pendingHomeLoad = null
        tab.openedFromHome = false
        showHomeState(tab)
    }

    override fun onSessionStateChanged(tab: TabEntry, json: String?) {
        if (tab.isPrivate || tab.pendingRestore != null || json == null) return
        if (json == tab.sessionStateJson) return
        tab.sessionStateJson = json
        scheduleSave()
    }

    override fun openChildTab(parent: TabEntry): GeckoSession =
        newTabForEngine(private = parent.isPrivate, select = true, parentId = parent.id).second

    override fun recoverSession(tab: TabEntry) {
        if (entries[tab.id] !== tab) return
        val crashed = tab.session
        hooks.notify { it.onSessionClosed(tab.id, crashed) }
        promptRegistry.dismissAll(tab.id)
        delegates.detach(crashed)
        if (crashed.isOpen) crashed.close()

        val fresh = GeckoSession(GeckoSessionSettings(crashed.settings))
        tab.session = fresh
        tab.sessionCanGoBack = false
        tab.awaitingCommit = false
        delegates.attach(fresh, this)
        fresh.open(runtime)
        hooks.notify { it.onSessionCreated(tab.id, fresh, tab.isPrivate) }
        update(tab) { it.copy(isLoading = false, progress = 0, canGoForward = false, isFullscreen = false, isMediaPlaying = false) }

        val now = SystemClock.elapsedRealtime()
        val mayAutoRestore = CrashRecovery.mayAutoRestore(tab.lastCrashAt, now)
        tab.lastCrashAt = now
        val saved = snapshotOf(tab, selected = false)
        if (isSelected(tab)) {
            activate(tab)
            // A page that crashes again right after being restored would reload forever: it stays
            // dormant (URL and title kept) until the user reloads or reselects the tab.
            if (mayAutoRestore) applyRestore(tab, saved) else tab.pendingRestore = saved
        } else {
            fresh.setActive(false)
            tab.pendingRestore = saved
        }
    }

    // endregion

    private fun createEntry(id: String, private: Boolean, desktop: Boolean, parentId: String?): TabEntry {
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(private)
            .useTrackingProtection(true)
            .build()
        applyDesktopMode(sessionSettings, desktop)
        val state = TabState(
            id = id,
            url = ABOUT_BLANK,
            title = "",
            isPrivate = private,
            isDesktopMode = desktop,
            parentId = parentId?.takeIf { entries.containsKey(it) },
            lastAccessed = System.currentTimeMillis(),
        )
        return TabEntry(id, GeckoSession(sessionSettings), private, state)
    }

    private fun openAndRegister(entry: TabEntry) {
        delegates.attach(entry.session, this)
        register(entry)
        entry.session.open(runtime)
        hooks.notify { it.onSessionCreated(entry.id, entry.session, entry.isPrivate) }
    }

    private fun register(entry: TabEntry) {
        entries[entry.id] = entry
        entry.state = withDerivedState(entry, entry.state)
    }

    private fun desktopModeFor(parentId: String?): Boolean =
        parentId?.let { entries[it] }?.state?.isDesktopMode ?: settings.desktopModeDefault

    private fun applyDesktopMode(sessionSettings: GeckoSessionSettings, desktop: Boolean) {
        sessionSettings.userAgentMode =
            if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        sessionSettings.viewportMode =
            if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }

    private fun goHome(entry: TabEntry) {
        val home = settings.homePage
        if (home == AppSettings.HOME_PAGE_BUILT_IN || home.isBlank()) {
            if (!entry.isShowingHome) showHome(entry)
        } else {
            load(entry, home)
        }
    }

    /** @param replaceHistory the home entry replaces the current history entry instead of following it */
    private fun showHome(entry: TabEntry, replaceHistory: Boolean = false) {
        entry.homeCapable = true
        entry.openedFromHome = false
        entry.pendingHomeLoad = null
        entry.pendingRestore = null
        showHomeState(entry)
        if (replaceHistory) {
            entry.session.load(GeckoSession.Loader().uri(ABOUT_BLANK).flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY))
        } else {
            entry.session.loadUri(ABOUT_BLANK)
        }
    }

    private fun showHomeState(entry: TabEntry) {
        entry.securityOrigin = null
        entry.awaitingCommit = false
        update(entry) {
            it.copy(
                url = AppSettings.HOME_PAGE_BUILT_IN,
                title = homeTitle,
                isLoading = false,
                progress = 0,
                security = SecurityLevel.NONE,
                isFullscreen = false,
            )
        }
    }

    /** @param backReturnsHome when leaving the home page, Back from the new page returns to it */
    private fun load(entry: TabEntry, url: String, backReturnsHome: Boolean = true) {
        if (url == AppSettings.HOME_PAGE_BUILT_IN) {
            showHome(entry)
            return
        }
        if (ExternalSchemes.isExternal(url)) {
            eventBus.emit(BrowserEvent.OpenExternal(url))
            return
        }
        entry.pendingRestore = null
        if (entry.isShowingHome) {
            // Leave the home UI right away; reverted by onLoadDenied if nothing commits.
            entry.pendingHomeLoad = url
            entry.openedFromHome = backReturnsHome
            update(entry) { it.copy(url = url, title = "", security = SecurityLevels.provisional(url)) }
        }
        entry.session.loadUri(url)
    }

    private fun activate(entry: TabEntry) {
        runtime.webExtensionController.setTabActive(entry.session, true)
        entry.session.setActive(true)
    }

    private fun deactivate(entry: TabEntry) {
        runtime.webExtensionController.setTabActive(entry.session, false)
        entry.session.setActive(false)
    }

    private fun removeEntry(entry: TabEntry) {
        promptRegistry.dismissAll(entry.id)
        entries.remove(entry.id)
        val grandParent = entry.state.parentId?.takeIf { entries.containsKey(it) }
        entries.values.filter { it.state.parentId == entry.id }.forEach { child ->
            update(child) { it.copy(parentId = grandParent) }
        }
        hooks.notify { it.onSessionClosed(entry.id, entry.session) }
        delegates.detach(entry.session)
        if (entry.session.isOpen) entry.session.close()
    }

    private fun applyRestore(entry: TabEntry, saved: SavedTab) {
        entry.pendingRestore = null
        val sessionState = saved.sessionState?.let { GeckoSession.SessionState.fromString(it) }
        when {
            sessionState != null -> {
                entry.sessionStateJson = saved.sessionState
                entry.session.restoreState(sessionState)
            }
            saved.url == AppSettings.HOME_PAGE_BUILT_IN || saved.url == ABOUT_BLANK -> showHome(entry)
            else -> entry.session.loadUri(saved.url)
        }
    }

    private fun restoreSaved(saved: List<SavedTab>) {
        val restorable = saved.filter { it.id.isNotBlank() && !entries.containsKey(it.id) }.distinctBy { it.id }
        if (restorable.isEmpty()) {
            if (entries.values.none { !it.isPrivate }) {
                newTab(url = null, private = false, select = _selectedTabId.value == null)
            }
            return
        }
        // Restored tabs are older than anything opened while they were loading: put them first.
        val openedMeanwhile = entries.values.toList()
        entries.clear()
        val toSelect = (restorable.firstOrNull { it.selected } ?: restorable.last()).id
        val now = System.currentTimeMillis()
        restorable.forEachIndexed { index, savedTab ->
            val entry = createEntry(savedTab.id, private = false, desktop = savedTab.isDesktopMode, parentId = null)
            entry.state = entry.state.copy(
                url = savedTab.url,
                title = savedTab.title,
                lastAccessed = if (savedTab.id == toSelect) now else now - (restorable.size - index),
            )
            entry.openedFromHome = savedTab.url != AppSettings.HOME_PAGE_BUILT_IN
            entry.sessionStateJson = savedTab.sessionState
            entry.pendingRestore = savedTab
            openAndRegister(entry)
            entry.session.setActive(false)
        }
        openedMeanwhile.forEach { entries[it.id] = it }
        // Keep the selection of a tab opened meanwhile (e.g. a link shared to the app).
        if (_selectedTabId.value == null) selectTab(toSelect)
        publish()
    }

    private fun installRuntimeHooks() {
        runtime.setServiceWorkerDelegate { _ -> GeckoResult.fromValue(openTabForServiceWorker()) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    flushState()
                }
            },
        )
    }

    /** clients.openWindow() from a service worker: an open, selected tab that GeckoView navigates. */
    private fun openTabForServiceWorker(): GeckoSession {
        val entry = createEntry(UUID.randomUUID().toString(), private = false, desktop = settings.desktopModeDefault, parentId = null)
        entry.homeCapable = false
        openAndRegister(entry)
        selectTab(entry.id)
        return entry.session
    }

    /** App went to the background: ask Gecko for fresh session state and save immediately. */
    private fun flushState() {
        entries.values.filter { !it.isPrivate && it.pendingRestore == null }.forEach { it.session.flushSessionState() }
        if (restored) {
            persistence.saveNow(snapshot())
            // flushSessionState() reports asynchronously; save once more when it arrives.
            scheduleSave()
        }
    }

    private fun publish() {
        _tabs.value = entries.values.map { it.state }
        _selectedTab.value = _selectedTabId.value?.let { entries[it]?.state }
        scheduleSave()
    }

    private fun scheduleSave() {
        if (restored) persistence.schedule(::snapshot)
    }

    private fun snapshot(): List<SavedTab> {
        val normal = entries.values.filter { !it.isPrivate }
        val selectedId = _selectedTabId.value?.takeIf { id -> normal.any { it.id == id } }
            ?: normal.maxByOrNull { it.state.lastAccessed }?.id
        return normal.map { snapshotOf(it, selected = it.id == selectedId) }
    }

    private fun snapshotOf(entry: TabEntry, selected: Boolean): SavedTab =
        entry.pendingRestore?.copy(selected = selected, isDesktopMode = entry.state.isDesktopMode)
            ?: SavedTab(
                id = entry.id,
                url = entry.state.url,
                title = entry.state.title,
                isDesktopMode = entry.state.isDesktopMode,
                selected = selected,
                sessionState = entry.sessionStateJson,
            )

    private fun candidates(): List<TabSelection.Candidate> =
        entries.values.map { TabSelection.Candidate(it.id, it.isPrivate, it.state.lastAccessed) }

    /** Back is possible through the engine, to the home page, or by closing a child tab. */
    private fun withDerivedState(tab: TabEntry, state: TabState): TabState {
        val parentId = state.parentId
        val canGoBack = tab.sessionCanGoBack ||
            (tab.openedFromHome && !state.isHome) ||
            (parentId != null && entries.containsKey(parentId))
        return if (state.canGoBack == canGoBack) state else state.copy(canGoBack = canGoBack)
    }

    private companion object {
        const val ABOUT_BLANK = "about:blank"
        const val INITIAL_PROGRESS = 5
    }
}
