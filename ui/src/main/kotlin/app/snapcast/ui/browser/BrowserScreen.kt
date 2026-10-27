package app.snapcast.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapcast.browser.TabState
import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.CastResult
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.util.UrlUtils
import app.snapcast.ui.R
import app.snapcast.ui.components.FullscreenEffect
import app.snapcast.ui.components.GeckoViewHost
import app.snapcast.ui.components.MiniController
import app.snapcast.ui.home.HomeScreen
import app.snapcast.ui.report.ReportProblemDialog
import app.snapcast.ui.util.copyToClipboard
import app.snapcast.ui.util.openWithOtherApp
import app.snapcast.ui.util.shareText
import app.snapcast.ui.video.CastErrorDialog
import app.snapcast.ui.video.DrmDialog
import app.snapcast.ui.video.VideoActions
import app.snapcast.ui.video.VideoFab
import app.snapcast.ui.video.VideoFabTip
import app.snapcast.ui.video.VideoSheet
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession

/** Destinations reachable from the browser screen. */
@Immutable
internal class BrowserNavigation(
    val openTabs: () -> Unit,
    val openHistory: () -> Unit,
    val openBookmarks: () -> Unit,
    val openSettings: () -> Unit,
    val openAddons: () -> Unit,
    val openCastController: () -> Unit,
    val openLocalMedia: () -> Unit,
)

/**
 * The browser: top bar, progress, home page or web content, the video button and sheet, the cast
 * mini controller, context menus, snackbars, fullscreen and back handling.
 */
@Composable
internal fun BrowserScreen(
    navigation: BrowserNavigation,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val focusManager = LocalFocusManager.current

    val tab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val tabCount by viewModel.tabCount.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val detectorReady by viewModel.isDetectorReady.collectAsStateWithLifecycle()
    val isBookmarked by viewModel.isBookmarked.collectAsStateWithLifecycle()
    val extensionActions by viewModel.extensionActions.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val playback by viewModel.castPlayback.collectAsStateWithLifecycle()
    val device by viewModel.castDevice.collectAsStateWithLifecycle()
    val connection by viewModel.castConnection.collectAsStateWithLifecycle()
    val contextMenu by viewModel.contextMenu.collectAsStateWithLifecycle()
    val openedFromHome by viewModel.openedFromHome.collectAsStateWithLifecycle()
    val failedPosters by viewModel.failedPosters.collectAsStateWithLifecycle()

    val addressState = remember { AddressBarState() }
    val snackbarHostState = remember { SnackbarHostState() }
    var showVideoSheet by rememberSaveable { mutableStateOf(false) }
    var showDrmDialog by remember { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var castError by remember { mutableStateOf<CastResult.Error?>(null) }

    val currentTab = tab
    val fullscreen = currentTab?.isFullscreen == true
    FullscreenEffect(fullscreen)
    LaunchedEffect(fullscreen) {
        if (fullscreen) {
            focusManager.clearFocus()
            showVideoSheet = false
        }
    }

    val castGate = rememberCastPermissionGate(viewModel)
    val videoActions = rememberVideoActions(
        viewModel = viewModel,
        castGate = castGate,
        onExplainDrm = { showDrmDialog = true },
        snackbarHostState = snackbarHostState,
    )

    BrowserEventsEffect(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        callbacks = BrowserEventCallbacks(
            onCastError = { error -> if (castError != error) castError = error },
            onLoadUrl = viewModel::loadUrl,
            onReloadTab = viewModel::reload,
            onSelectTab = viewModel::selectTab,
        ),
    )

    val overlayOpen = showVideoSheet || contextMenu != null || addressState.editing
    val backAction = viewModel.backAction(currentTab, overlayOpen, openedFromHome)
    BackHandler(enabled = backAction != BackAction.SYSTEM) {
        if (backAction == BackAction.DISMISS_OVERLAY) {
            showVideoSheet = false
            viewModel.dismissContextMenu()
            focusManager.clearFocus()
        } else {
            viewModel.performBack(backAction)
        }
    }

    val submit: (String) -> Unit = { input ->
        focusManager.clearFocus()
        viewModel.loadInput(input)
    }
    val openUrl: (String) -> Unit = { url ->
        focusManager.clearFocus()
        viewModel.loadUrl(url)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (fullscreen) {
                    Modifier
                } else {
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                },
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = !fullscreen, enter = expandVertically(), exit = shrinkVertically()) {
                BrowserTopBar(
                    tab = currentTab,
                    tabCount = tabCount,
                    state = addressState,
                    showCastButton = connection != CastConnectionState.UNAVAILABLE,
                    createCastButton = viewModel::createCastButton,
                    onQueryChange = viewModel::onAddressQueryChanged,
                    onSubmit = submit,
                    onOpenTabs = navigation.openTabs,
                    menu = { expanded, onDismiss ->
                        BrowserMenu(
                            expanded = expanded,
                            onDismiss = onDismiss,
                            tab = currentTab,
                            isBookmarked = isBookmarked,
                            extensionActions = extensionActions,
                            actions = rememberMenuActions(viewModel, navigation, currentTab, onReportProblem = { showReport = true }),
                        )
                    },
                )
            }
            LoadingBar(tab = currentTab, visible = !fullscreen)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (fullscreen) {
                            Modifier
                        } else {
                            Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        },
                    ),
            ) {
                PageContent(
                    tab = currentTab,
                    session = { id -> viewModel.session(id) },
                    editing = addressState.editing,
                    onTapWhileEditing = { focusManager.clearFocus() },
                    onSubmit = submit,
                    onOpenUrl = openUrl,
                )
                SuggestionsPanel(
                    visible = addressState.editing,
                    suggestions = suggestions,
                    onPick = { openUrl(it.url) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                BottomOverlays(
                    videos = videos,
                    fullscreen = fullscreen,
                    editing = addressState.editing,
                    tipShown = settings.videoButtonTipShown,
                    playback = playback,
                    deviceName = device?.name,
                    snackbarHostState = snackbarHostState,
                    viewModel = viewModel,
                    onOpenVideos = {
                        viewModel.markVideoTipShown()
                        viewModel.rescanVideos()
                        showVideoSheet = true
                    },
                    onOpenCastController = navigation.openCastController,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (showVideoSheet) {
        VideoSheet(
            videos = videos,
            isDetectorReady = detectorReady,
            actions = videoActions,
            onRescan = viewModel::rescanVideos,
            onNeedThumbnails = viewModel::requestThumbnails,
            onPosterFailed = viewModel::posterFailed,
            onDismiss = { showVideoSheet = false },
            failedPosters = failedPosters,
            onReportProblem = { showReport = true }.takeIf { currentTab != null && !currentTab.isPrivate && !currentTab.isHome },
        )
    }

    if (showReport) {
        val reportTab = currentTab
        if (reportTab != null && !reportTab.isHome && !reportTab.isPrivate) {
            ReportProblemDialog(
                siteHost = UrlUtils.hostOf(reportTab.url)?.removePrefix("www.") ?: reportTab.url,
                buildReport = viewModel::siteReport,
                onOpenInApp = viewModel::openInNewTab,
                onDismiss = { showReport = false },
            )
        } else {
            // The tab changed to one a report can't be about (home, private): drop the request.
            LaunchedEffect(Unit) { showReport = false }
        }
    }

    contextMenu?.let { menuState ->
        ContextMenuSheet(
            state = menuState,
            isPrivateTab = currentTab?.isPrivate == true,
            actions = rememberContextMenuActions(viewModel, castGate, snackbarHostState) { showDrmDialog = true },
            onDismiss = viewModel::dismissContextMenu,
        )
    }

    if (showDrmDialog) DrmDialog(onDismiss = { showDrmDialog = false })
    castError?.let { error ->
        CastErrorDialog(kind = error.kind, message = error.message, onDismiss = { castError = null })
    }
}

/** Thin animated page-load progress bar under the top bar. */
@Composable
private fun LoadingBar(tab: TabState?, visible: Boolean) {
    val loading = visible && tab != null && tab.isLoading && !tab.isHome
    val progress by animateFloatAsState(
        targetValue = ((tab?.progress ?: 0).coerceIn(0, 100)) / 100f,
        label = "pageProgress",
    )
    AnimatedVisibility(visible = loading, enter = fadeIn(), exit = fadeOut()) {
        LinearProgressIndicator(
            progress = { progress.coerceAtLeast(0.05f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}

/** Home page or web content, cross-faded. Taps on the content end address-bar editing. */
@Composable
private fun PageContent(
    tab: TabState?,
    session: (String) -> GeckoSession?,
    editing: Boolean,
    onTapWhileEditing: () -> Unit,
    onSubmit: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(editing) {
                if (!editing) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onTapWhileEditing()
                }
            },
    ) {
        Crossfade(targetState = tab == null || tab.isHome, label = "pageContent") { home ->
            if (home) {
                HomeScreen(
                    isPrivate = tab?.isPrivate == true,
                    onSubmit = onSubmit,
                    onOpenUrl = onOpenUrl,
                )
            } else {
                GeckoViewHost(
                    session = tab?.let { session(it.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Snackbars, the video button (+ first-run tip) and the cast mini controller, stacked bottom-up. */
@Composable
private fun BottomOverlays(
    videos: List<DetectedVideo>,
    fullscreen: Boolean,
    editing: Boolean,
    tipShown: Boolean,
    playback: RemotePlaybackState?,
    deviceName: String?,
    snackbarHostState: SnackbarHostState,
    viewModel: BrowserViewModel,
    onOpenVideos: () -> Unit,
    onOpenCastController: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fabVisible = videos.isNotEmpty() && !fullscreen && !editing
    // Keep the last state so the mini controller can animate out after playback ends.
    var shownPlayback by remember { mutableStateOf(playback) }
    LaunchedEffect(playback) {
        if (playback != null) shownPlayback = playback
    }
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.fillMaxWidth())
        VideoFabTip(
            visible = fabVisible && !tipShown,
            onDismiss = viewModel::markVideoTipShown,
            modifier = Modifier.padding(end = 16.dp, start = 48.dp),
        )
        VideoFab(
            count = videos.size,
            visible = fabVisible,
            pulses = viewModel.videoPulses,
            onClick = onOpenVideos,
            modifier = Modifier.padding(end = 16.dp, bottom = 16.dp, top = 4.dp),
        )
        AnimatedVisibility(
            visible = playback != null && !fullscreen && !editing,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            shownPlayback?.let { state ->
                MiniController(
                    playback = state,
                    deviceName = deviceName,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onOpen = onOpenCastController,
                )
            }
        }
    }
}

@Composable
private fun rememberVideoActions(
    viewModel: BrowserViewModel,
    castGate: (() -> Unit) -> Unit,
    onExplainDrm: () -> Unit,
    snackbarHostState: SnackbarHostState,
): VideoActions {
    val context = LocalContext.current
    val noAppMessage = stringResource(R.string.ui_video_no_app)
    val chooserTitle = stringResource(R.string.ui_video_open_with)
    val clipLabel = stringResource(R.string.ui_video_clip_label)
    val scope = rememberCoroutineScope()
    return remember(viewModel, castGate, context) {
        VideoActions(
            cast = { video, variant, subtitle, direct ->
                if (video.isDrmProtected) {
                    onExplainDrm()
                } else {
                    castGate { viewModel.cast(video, variant, subtitle, direct) }
                }
            },
            playOnPhone = { video, variant ->
                if (video.isDrmProtected) onExplainDrm() else viewModel.playOnPhone(context, video, variant)
            },
            copyLink = { video -> copyToClipboard(context, clipLabel, video.url) },
            openWithOtherApp = { video ->
                val opened = openWithOtherApp(context, video.url, video.mimeType ?: video.type.defaultMimeType, chooserTitle)
                if (!opened) {
                    scope.launch { snackbarHostState.showSnackbar(noAppMessage) }
                }
            },
            addToQueue = { video, variant ->
                if (video.isDrmProtected) onExplainDrm() else castGate { viewModel.addToQueue(video, variant) }
            },
            explainDrm = { onExplainDrm() },
            canCastDirectly = viewModel::canCastDirectly,
        )
    }
}

@Composable
private fun rememberContextMenuActions(
    viewModel: BrowserViewModel,
    castGate: (() -> Unit) -> Unit,
    snackbarHostState: SnackbarHostState,
    onExplainDrm: () -> Unit,
): ContextMenuActions {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.ui_ctx_copied)
    val clipLabel = stringResource(R.string.ui_ctx_clip_label)
    val shareTitle = stringResource(R.string.ui_browser_share)
    val scope = rememberCoroutineScope()
    return remember(viewModel, castGate, context) {
        ContextMenuActions(
            openInNewTab = viewModel::openInBackgroundTab,
            copy = { url ->
                if (copyToClipboard(context, clipLabel, url)) {
                    scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                }
            },
            share = { url, title -> shareText(context, url, title, shareTitle) },
            castVideo = { video ->
                if (video.isDrmProtected) onExplainDrm() else castGate { viewModel.cast(video) }
            },
            playVideo = { video ->
                if (video.isDrmProtected) onExplainDrm() else viewModel.playOnPhone(context, video)
            },
        )
    }
}

@Composable
private fun rememberMenuActions(
    viewModel: BrowserViewModel,
    navigation: BrowserNavigation,
    tab: TabState?,
    onReportProblem: () -> Unit,
): BrowserMenuActions {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.ui_browser_share)
    return remember(viewModel, navigation, tab, context) {
        BrowserMenuActions(
            back = viewModel::goBack,
            forward = viewModel::goForward,
            reloadOrStop = viewModel::reloadOrStop,
            toggleBookmark = viewModel::toggleBookmark,
            newTab = { viewModel.newTab(private = false) },
            newPrivateTab = { viewModel.newTab(private = true) },
            history = navigation.openHistory,
            bookmarks = navigation.openBookmarks,
            addons = navigation.openAddons,
            extensionAction = viewModel::clickExtensionAction,
            toggleDesktopMode = viewModel::toggleDesktopMode,
            share = {
                if (tab != null && !tab.isHome) shareText(context, tab.url, tab.title, shareTitle)
            },
            castLocal = navigation.openLocalMedia,
            reportProblem = onReportProblem,
            settings = navigation.openSettings,
        )
    }
}
