package app.snapcast.ui.browser

import android.content.Context
import android.util.Log
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
import app.snapcast.cast.CastEvent
import app.snapcast.cast.CastResult
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.core.model.AppSettings
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.report.ProblemKind
import app.snapcast.core.report.ReportEnvironment
import app.snapcast.core.report.ReportEvent
import app.snapcast.core.report.SiteReport
import app.snapcast.core.util.CookieHeaders
import app.snapcast.core.util.UrlUtils
import app.snapcast.data.bookmarks.BookmarkRepository
import app.snapcast.data.diagnostics.DiagnosticsLog
import app.snapcast.data.history.HistoryRepository
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.detection.DetectedVideoRepository
import app.snapcast.player.PlaybackQuality
import app.snapcast.player.PlaybackRequest
import app.snapcast.player.PlayerLauncher
import coil3.network.HttpException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoSession
import javax.inject.Inject

private const val TAG = "SnapcastBrowser"
private const val SUGGESTION_DEBOUNCE_MS = 150L
private const val STOP_TIMEOUT_MS = 5_000L

/** Thumbnail requests closer together than this are dropped (the page captures each video once anyway). */
private const val THUMBNAIL_REQUEST_INTERVAL_MS = 1_500L

/** How long a reloaded page has to show the refused video again before the user is asked to pick it. */
private const val REPLAY_TIMEOUT_MS = 30_000L
/** How long the reloaded page's candidates must stay the same before one is played. */
private const val REPLAY_SETTLE_MS = 2_000L

/** Cookies handed to the phone player travel in an Intent, which has a size limit. */
private const val MAX_PLAYER_COOKIE_CHARS = 32_000

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
    private val diagnostics: DiagnosticsLog,
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

    /** What the receiver plays; null when it plays nothing (also once stopped, or after its last item). */
    val castPlayback: StateFlow<RemotePlaybackState?> = castController.playback
        .map { it?.takeUnless(RemotePlaybackState::isEnded) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, castController.playback.value?.takeUnless { it.isEnded })

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

    private var lastThumbnailRequestAt = 0L
    private var replayJob: Job? = null

    /**
     * Posters that failed to load, per tab: not requested again (the list shows the frame capture instead) and
     * reported once, while the tab's list still has them. A reload or another page empties the list, and with it
     * this memory, so the poster is tried again there.
     */
    private val failedPostersByTab = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** The selected tab's failed posters. */
    val failedPosters: StateFlow<Set<String>> = combine(browserTabs.selectedTabId, failedPostersByTab) { id, byTab ->
        id?.let(byTab::get).orEmpty()
    }.stateIn(viewModelScope, whileSubscribed, emptySet())

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
            castController.events.collect { event ->
                // A failure after the cast started (deferred cast, receiver error): noted for its page's reports.
                if (event is CastEvent.Error && event.pageUrl != null) {
                    diagnostics.record("cast", "${event.kind}: ${event.message}", event.pageUrl)
                }
                uiEventChannel.trySend(BrowserUiEvent.Cast(event))
            }
        }
        viewModelScope.launch {
            browserTabs.tabs.collect { tabs ->
                val ids = tabs.mapTo(HashSet()) { it.id }
                _openedFromHome.update { current -> current.filterTo(HashSet()) { it in ids } }
            }
        }
        viewModelScope.launch {
            videoRepository.videosByTab.collect { byTab ->
                failedPostersByTab.update { current ->
                    if (current.isEmpty()) return@update current
                    current.mapNotNull { (tabId, posters) ->
                        val listed = byTab[tabId].orEmpty().mapNotNullTo(HashSet()) { it.posterUrl }
                        posters.filterTo(HashSet()) { it in listed }.takeIf { it.isNotEmpty() }?.let { tabId to it }
                    }.toMap()
                }
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

    /**
     * A poster in the video list could not be loaded: remember it for this page (it is not requested again), note
     * why once (host and status only, never for a private tab) and ask the page for a frame instead.
     */
    fun posterFailed(video: DetectedVideo, error: Throwable) {
        val poster = video.posterUrl ?: return
        if (poster in failedPostersByTab.value[video.tabId].orEmpty()) return
        failedPostersByTab.update { it + (video.tabId to (it[video.tabId].orEmpty() + poster)) }
        if (!video.isPrivate) {
            val host = UrlUtils.hostOf(poster) ?: "?"
            val reason = if (error is HttpException) "HTTP ${error.response.code}" else error::class.java.simpleName
            Log.w(TAG, "Poster from $host failed: $reason")
            diagnostics.record("poster", "$reason from $host", video.pageUrl)
        }
        requestThumbnails()
    }

    /**
     * A problem report about the selected tab's site, or null on the home page or in a private tab (nothing of a
     * private tab is ever put in a report). Built on demand; nothing is sent from here.
     */
    fun siteReport(kind: ProblemKind, details: String, includePageUrl: Boolean, environment: ReportEnvironment): SiteReport? {
        val tab = selectedTab.value?.takeUnless { it.isHome || it.isPrivate } ?: return null
        if (!UrlUtils.isHttp(tab.url)) return null
        return SiteReport(
            pageUrl = tab.url,
            kind = kind,
            details = details,
            includePageUrl = includePageUrl,
            environment = environment,
            videos = videos.value,
            // Only this site's failures: a report never tells about other sites the user visited.
            events = diagnostics.recent()
                .filter { it.site != null && it.site == UrlUtils.hostOf(tab.url)?.removePrefix("www.") }
                .map { ReportEvent(it.atMs, it.source, it.message) },
        )
    }

    /** The open video list has videos without a usable picture: ask the page for frame captures. */
    fun requestThumbnails() {
        val now = System.currentTimeMillis()
        if (now - lastThumbnailRequestAt < THUMBNAIL_REQUEST_INTERVAL_MS) return
        lastThumbnailRequestAt = now
        browserTabs.selectedTabId.value?.let(videoRepository::requestThumbnails)
    }

    fun cast(video: DetectedVideo, variant: VideoVariant? = null, subtitle: SubtitleTrack? = null, direct: Boolean = false) {
        viewModelScope.launch {
            val result = safeCast { castController.cast(video, variant, subtitle, direct) }
            recordCastFailure(video, result)
            uiEventChannel.trySend(BrowserUiEvent.CastOutcome(result, video, queued = false))
        }
    }

    fun addToQueue(video: DetectedVideo, variant: VideoVariant? = null) {
        viewModelScope.launch {
            val result = safeCast { castController.addToQueue(video, variant) }
            recordCastFailure(video, result)
            uiEventChannel.trySend(BrowserUiEvent.CastOutcome(result, video, queued = true))
        }
    }

    private fun recordCastFailure(video: DetectedVideo, result: CastResult) {
        if (result is CastResult.Error && !video.isPrivate) {
            diagnostics.record("cast", "${result.kind} for ${video.sourceDomain}: ${result.message}", video.pageUrl)
        }
    }

    private suspend fun safeCast(block: suspend () -> CastResult): CastResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CastResult.Error(CastErrorKind.LOAD_FAILED, e.message.orEmpty())
    }

    /**
     * Plays [video] on the phone, pinned to [variant]'s quality when one was picked ([quality] when known otherwise,
     * e.g. after a reload, before the stream's variants are), from [startPositionMs] when given.
     */
    fun playOnPhone(
        context: Context,
        video: DetectedVideo,
        variant: VideoVariant? = null,
        quality: PlaybackQuality? = variant?.let { PlaybackQuality(it.width, it.height, it.bandwidth) },
        startPositionMs: Long? = null,
    ) {
        playerLauncher.play(
            context,
            PlaybackRequest(
                url = video.url,
                title = video.displayTitle,
                type = video.type,
                mimeType = video.mimeType,
                headers = video.headers.toMap(),
                cookiesByHost = CookieHeaders.bounded(video.cookiesByHost, listOfNotNull(UrlUtils.hostOf(video.url)), MAX_PLAYER_COOKIE_CHARS),
                subtitles = video.subtitles,
                posterUrl = video.posterUrl,
                pageUrl = video.pageUrl,
                isPrivate = video.isPrivate,
                quality = quality,
                startPositionMs = startPositionMs,
            ),
        )
    }

    /** "Reload page" requests from the player, run by the screen once the browser is shown again. */
    val reloadRequests: Flow<PlaybackRequest> = playerLauncher.reloadRequests

    /**
     * The player was refused [request]'s link (it expired) and the user chose "Reload page": reload the page it
     * came from (in its tab when still open), wait for the same video to show up again with a fresh link, and
     * play it. No extra requests: the page itself fetches the new link.
     */
    fun reloadAndReplay(request: PlaybackRequest) {
        val pageUrl = request.pageUrl?.takeIf(UrlUtils::isHttp) ?: return
        val tabs = browserTabs.tabs.value
        val sameDocument = { url: String? -> url?.substringBefore('#') == pageUrl.substringBefore('#') }
        val samePlace = { tab: TabState -> sameDocument(tab.url) && tab.isPrivate == request.isPrivate }
        val tab = tabs.firstOrNull { it.id == browserTabs.selectedTabId.value && samePlace(it) } ?: tabs.firstOrNull(samePlace)
        val since = System.currentTimeMillis()
        val tabId = if (tab != null) {
            browserTabs.selectTab(tab.id)
            browserTabs.reload(tab.id)
            tab.id
        } else {
            browserTabs.newTab(url = pageUrl, private = request.isPrivate, select = true, parentId = null)
        }
        uiEventChannel.trySend(BrowserUiEvent.ReloadingForReplay)
        replayJob?.cancel()
        replayJob = viewModelScope.launch {
            val video = withTimeoutOrNull(REPLAY_TIMEOUT_MS) {
                VideoReplay.await(videoRepository.videosForTab(tabId), request.url, since, REPLAY_SETTLE_MS)
            }?.let { picked -> videoRepository.videosByTab.value[tabId]?.firstOrNull { it.id == picked.id } ?: picked }
            uiEventChannel.trySend(
                if (video != null) {
                    // The stream's variants are usually not known yet: the player matches the quality itself.
                    BrowserUiEvent.PlayOnPhone(video, request.quality, request.startPositionMs)
                } else {
                    BrowserUiEvent.ReplayNotFound
                },
            )
        }
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

    /** Opens [url] in a new tab next to the current one and shows it. */
    fun openInNewTab(url: String) {
        browserTabs.newTab(url = url, private = false, select = true, parentId = browserTabs.selectedTabId.value)
    }

    /** Opens [url] in a background tab whose parent is the current tab, and offers to switch to it. */
    fun openInBackgroundTab(url: String, private: Boolean) {
        val parent = browserTabs.selectedTabId.value
        val tabId = browserTabs.newTab(url = url, private = private, select = false, parentId = parent)
        uiEventChannel.trySend(BrowserUiEvent.OpenedInBackground(tabId, private))
    }
}
