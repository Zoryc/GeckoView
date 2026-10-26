package app.snapcast.ui.browser

import android.content.Context
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.addons.AddonsRepository
import app.snapcast.addons.ExtensionAction
import app.snapcast.browser.BrowserEvent
import app.snapcast.browser.BrowserTabs
import app.snapcast.browser.TabState
import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.CastController
import app.snapcast.cast.CastDeviceInfo
import app.snapcast.cast.CastErrorKind
import app.snapcast.cast.CastResult
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.core.model.AppSettings
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.data.bookmarks.BookmarkRepository
import app.snapcast.data.history.HistoryRepository
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.detection.DetectedVideoRepository
import app.snapcast.player.PlaybackRequest
import app.snapcast.player.PlayerLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import javax.inject.Inject

private const val SUGGESTION_DEBOUNCE_MS = 150L
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * State and actions of the browser screen: the selected tab, address-bar suggestions, detected videos,
 * extension toolbar actions, casting and the back-navigation policy. All engine calls happen on the
 * main thread (UI callbacks and viewModelScope, which uses Dispatchers.Main.immediate).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
internal class BrowserViewModel @Inject constructor(
    private val browserTabs: BrowserTabs,
    private val videoRepository: DetectedVideoRepository,
    private val addonsRepository: AddonsRepository,
    private val castController: CastController,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val playerLauncher: PlayerLauncher,
) : ViewModel() {

    private val whileSubscribed = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS)

    val selectedTab: StateFlow<TabState?> = browserTabs.selectedTab

    /** Number of open tabs in the selected tab's mode (normal or private). */
    val tabCount: StateFlow<Int> = combine(browserTabs.tabs, browserTabs.selectedTab) { tabs, selected ->
        val isPrivate = selected?.isPrivate ?: false
        tabs.count { it.isPrivate == isPrivate }
    }.stateIn(viewModelScope, whileSubscribed, 0)

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val videos: StateFlow<List<DetectedVideo>> = browserTabs.selectedTabId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else videoRepository.videosForTab(id) }
        .stateIn(viewModelScope, whileSubscribed, emptyList())

    val isDetectorReady: StateFlow<Boolean> = videoRepository.isDetectorReady

    /** Each video newly found in the selected tab (pulses the video button). */
    val videoPulses: Flow<DetectedVideo> = videoRepository.newVideos
        .filter { it.tabId == browserTabs.selectedTabId.value }

    val isBookmarked: StateFlow<Boolean> = browserTabs.selectedTab
        .map { tab -> tab?.takeUnless { it.isHome }?.url }
        .distinctUntilChanged()
        .flatMapLatest { url -> if (url == null) flowOf(false) else bookmarkRepository.observeIsBookmarked(url) }
        .stateIn(viewModelScope, whileSubscribed, false)

    val extensionActions: StateFlow<List<ExtensionAction>> = browserTabs.selectedTabId
        .flatMapLatest { id -> addonsRepository.actions(id) }
        .stateIn(viewModelScope, whileSubscribed, emptyList())

    private val addressQuery = MutableStateFlow("")

    val suggestions: StateFlow<List<AddressSuggestion>> = addressQuery
        .debounce { if (it.isBlank()) 0L else SUGGESTION_DEBOUNCE_MS }
        .mapLatest { query -> buildSuggestions(query) }
        .stateIn(viewModelScope, whileSubscribed, emptyList())

    val castPlayback: StateFlow<RemotePlaybackState?> = castController.playback

    val castDevice: StateFlow<CastDeviceInfo?> = castController.target.device

    val castConnection: StateFlow<CastConnectionState> = castController.connectionState

    private val _contextMenu = MutableStateFlow<ContextMenuState?>(null)
    val contextMenu: StateFlow<ContextMenuState?> = _contextMenu.asStateFlow()

    /** Tabs whose current page was loaded from the built-in home page (back returns home). */
    private val _openedFromHome = MutableStateFlow<Set<String>>(emptySet())
    val openedFromHome: StateFlow<Set<String>> = _openedFromHome.asStateFlow()

    // Never suspends the producers (engine event flows): old events are dropped if the UI is away for long.
    private val uiEventChannel = Channel<BrowserUiEvent>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val uiEvents: Flow<BrowserUiEvent> = uiEventChannel.receiveAsFlow()

    /** POST_NOTIFICATIONS is requested at most once per process, before the first cast. */
    var notificationPermissionRequested: Boolean = false

    init {
        viewModelScope.launch {
            browserTabs.events.collect { event ->
                if (event is BrowserEvent.ContextMenu) {
                    _contextMenu.value = contextMenuFor(event)
                } else {
                    uiEventChannel.trySend(BrowserUiEvent.Browser(event))
                }
            }
        }
        viewModelScope.launch {
            castController.events.collect { uiEventChannel.trySend(BrowserUiEvent.Cast(it)) }
        }
        viewModelScope.launch {
            browserTabs.tabs.collect { tabs ->
                val ids = tabs.mapTo(HashSet()) { it.id }
                _openedFromHome.update { current -> current.filterTo(HashSet()) { it in ids } }
            }
        }
    }

    // ---- Address bar -----------------------------------------------------------------------------

    fun onAddressQueryChanged(text: String) {
        addressQuery.value = text
    }

    /** Address bar / home search field: URL or search terms. */
    fun loadInput(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        addressQuery.value = ""
        val tabId = tabForNavigation()
        browserTabs.loadInput(tabId, text)
    }

    /** Suggestion, speed-dial tile or link fallback: an exact URL. */
    fun loadUrl(url: String) {
        addressQuery.value = ""
        val tabId = tabForNavigation()
        browserTabs.loadUrl(tabId, url)
    }

    /** The selected tab (marked as "opened from home" when it shows the home page), or a new one. */
    private fun tabForNavigation(): String {
        val tab = selectedTab.value
        val tabId = tab?.id ?: browserTabs.newTab()
        if (tab == null || tab.isHome) _openedFromHome.update { it + tabId }
        return tabId
    }

    private suspend fun buildSuggestions(query: String): List<AddressSuggestion> {
        if (query.isBlank()) return emptyList()
        return try {
            val history = historyRepository.suggestions(query.trim(), SuggestionMerger.DEFAULT_LIMIT)
            val bookmarks = bookmarkRepository.observeBookmarks().first()
            SuggestionMerger.merge(
                query = query,
                bookmarks = bookmarks.map { it.url to it.title },
                history = history.map { it.url to it.title },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- Navigation --------------------------------------------------------------------------------

    fun goBack() = withTab { browserTabs.goBack(it.id) }

    fun goForward() = withTab { browserTabs.goForward(it.id) }

    fun reloadOrStop() = withTab { if (it.isLoading) browserTabs.stop(it.id) else browserTabs.reload(it.id) }

    fun reload(tabId: String) = browserTabs.reload(tabId)

    fun newTab(private: Boolean) {
        browserTabs.newTab(private = private, select = true)
    }

    fun selectTab(tabId: String) = browserTabs.selectTab(tabId)

    /** The engine session shown for [tabId] (home tabs have one too). */
    fun session(tabId: String): GeckoSession? = browserTabs.session(tabId)

    fun toggleDesktopMode() = withTab { browserTabs.setDesktopMode(it.id, !it.isDesktopMode) }

    fun exitFullscreen() = withTab { browserTabs.exitFullscreen(it.id) }

    fun toggleBookmark() {
        val tab = selectedTab.value ?: return
        if (tab.isHome) return
        viewModelScope.launch {
            val bookmarked = bookmarkRepository.toggle(tab.url, tab.title.ifBlank { tab.url })
            uiEventChannel.trySend(BrowserUiEvent.BookmarkToggled(bookmarked))
        }
    }

    fun clickExtensionAction(action: ExtensionAction) {
        addonsRepository.clickAction(action.extensionId, browserTabs.selectedTabId.value)
    }

    /** What a back press does now; see [BackNavigation]. */
    fun backAction(tab: TabState?, overlayOpen: Boolean, openedFromHome: Set<String>): BackAction {
        val parentId = tab?.parentId
        return BackNavigation.decide(
            BackState(
                overlayOpen = overlayOpen,
                hasTab = tab != null,
                isFullscreen = tab?.isFullscreen == true,
                canGoBack = tab?.canGoBack == true,
                isHome = tab?.isHome == true,
                openedFromHome = tab != null && tab.id in openedFromHome,
                hasParent = parentId != null && browserTabs.tabs.value.any { it.id == parentId },
            ),
        )
    }

    fun performBack(action: BackAction) {
        val tab = selectedTab.value ?: return
        when (action) {
            BackAction.EXIT_FULLSCREEN -> browserTabs.exitFullscreen(tab.id)
            BackAction.GO_BACK -> browserTabs.goBack(tab.id)
            BackAction.GO_HOME -> {
                _openedFromHome.update { it - tab.id }
                browserTabs.goHome(tab.id)
            }
            BackAction.CLOSE_TAB -> {
                tab.parentId?.takeIf { parent -> browserTabs.tabs.value.any { it.id == parent } }
                    ?.let(browserTabs::selectTab)
                browserTabs.closeTab(tab.id)
            }
            BackAction.DISMISS_OVERLAY, BackAction.SYSTEM -> Unit
        }
    }

    private inline fun withTab(block: (TabState) -> Unit) {
        selectedTab.value?.let(block)
    }

    // ---- Videos & casting --------------------------------------------------------------------------

    /** The video sheet was opened: ask the page's content scripts for a fresh DOM scan. */
    fun rescanVideos() {
        browserTabs.selectedTabId.value?.let(videoRepository::requestRescan)
    }

    fun cast(video: DetectedVideo, variant: VideoVariant? = null, subtitle: SubtitleTrack? = null, direct: Boolean = false) {
        viewModelScope.launch {
            val result = safeCast { castController.cast(video, variant, subtitle, direct) }
            uiEventChannel.trySend(BrowserUiEvent.CastOutcome(result, video, queued = false))
        }
    }

    fun addToQueue(video: DetectedVideo) {
        viewModelScope.launch {
            val result = safeCast { castController.addToQueue(video) }
            uiEventChannel.trySend(BrowserUiEvent.CastOutcome(result, video, queued = true))
        }
    }

    private suspend fun safeCast(block: suspend () -> CastResult): CastResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CastResult.Error(CastErrorKind.LOAD_FAILED, e.message.orEmpty())
    }

    fun playOnPhone(context: Context, video: DetectedVideo) {
        playerLauncher.play(
            context,
            PlaybackRequest(
                url = video.url,
                title = video.displayTitle,
                type = video.type,
                mimeType = video.mimeType,
                headers = video.headers.toMap(),
                cookiesByHost = video.cookiesByHost,
                subtitles = video.subtitles,
                posterUrl = video.posterUrl,
                pageUrl = video.pageUrl,
                isPrivate = video.isPrivate,
            ),
        )
    }

    fun canCastDirectly(video: DetectedVideo): Boolean = castController.canCastDirectly(video)

    fun showDeviceChooser(activity: FragmentActivity) = castController.showDeviceChooser(activity)

    fun createCastButton(context: Context): View = castController.createCastButton(context)

    fun togglePlayPause() = castController.target.togglePlayPause()

    fun markVideoTipShown() {
        if (settings.value.videoButtonTipShown) return
        viewModelScope.launch { settingsRepository.update { it.copy(videoButtonTipShown = true) } }
    }

    // ---- Context menu ------------------------------------------------------------------------------

    private fun contextMenuFor(event: BrowserEvent.ContextMenu): ContextMenuState {
        val video = if (event.type == BrowserEvent.ContextElementType.VIDEO) {
            val tab = browserTabs.tabs.value.firstOrNull { it.id == event.tabId }
            ContextMenuVideos.resolve(
                srcUrl = event.srcUrl,
                elementTitle = event.title,
                page = ContextMenuVideos.Page(
                    tabId = event.tabId,
                    url = tab?.takeUnless { it.isHome }?.url,
                    title = tab?.title,
                    isPrivate = tab?.isPrivate ?: false,
                ),
                detected = videoRepository.videosByTab.value[event.tabId].orEmpty(),
                nowMs = System.currentTimeMillis(),
            )
        } else {
            null
        }
        return ContextMenuState(event, video)
    }

    fun dismissContextMenu() {
        _contextMenu.value = null
    }

    /** Opens [url] in a background tab whose parent is the current tab, and offers to switch to it. */
    fun openInBackgroundTab(url: String, private: Boolean) {
        val parent = browserTabs.selectedTabId.value
        val tabId = browserTabs.newTab(url = url, private = private, select = false, parentId = parent)
        uiEventChannel.trySend(BrowserUiEvent.OpenedInBackground(tabId, private))
    }
}
