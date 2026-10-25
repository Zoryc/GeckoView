package app.snapcast.cast.controller

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.app.MediaRouteDialogFactory
import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.CastController
import app.snapcast.cast.CastErrorKind
import app.snapcast.cast.CastEvent
import app.snapcast.cast.CastMedia
import app.snapcast.cast.CastResult
import app.snapcast.cast.CastSubtitle
import app.snapcast.cast.CastTarget
import app.snapcast.cast.PendingQueueItem
import app.snapcast.cast.R
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.cast.RemotePlayerState
import app.snapcast.cast.chromecast.CastContextProvider
import app.snapcast.cast.chromecast.ChromecastTarget
import app.snapcast.cast.media.CastMediaFactory
import app.snapcast.cast.media.CastMediaRules
import app.snapcast.cast.media.LanMediaProxy
import app.snapcast.cast.media.LocalMediaReader
import app.snapcast.cast.media.MediaProxy
import app.snapcast.cast.media.ResumePolicy
import app.snapcast.cast.media.SubtitleText
import app.snapcast.cast.net.NetworkHelper
import app.snapcast.cast.proxy.ProxyManager
import app.snapcast.cast.service.CastPlaybackService
import app.snapcast.core.media.SubtitleConverter
import app.snapcast.core.model.AppSettings
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.util.UrlUtils
import app.snapcast.data.di.ApplicationScope
import app.snapcast.data.playback.PlaybackPositionRepository
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.proxy.SubtitleSource
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CastController] over the Chromecast target, the local proxy and the casting service.
 *
 * All mutable state is confined to the main thread. [events] carries outcomes nobody is waiting for
 * (a request that ran after a device connected, receiver playback errors, the end of a session) plus
 * [CastEvent.Started] / [CastEvent.Queued] for every success; errors returned from a call are not
 * emitted again.
 */
@Singleton
internal class CastControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chromecast: ChromecastTarget,
    private val castContextProvider: CastContextProvider,
    private val proxyManager: ProxyManager,
    private val networkHelper: NetworkHelper,
    private val localMedia: LocalMediaReader,
    private val settingsRepository: SettingsRepository,
    private val positions: PlaybackPositionRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : CastController {

    override val target: CastTarget get() = chromecast

    override val connectionState: StateFlow<CastConnectionState> get() = chromecast.connectionState

    override val playback: StateFlow<RemotePlaybackState?> get() = chromecast.playback

    private val _events = MutableSharedFlow<CastEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    private val _pendingQueue = MutableStateFlow<List<PendingQueueItem>>(emptyList())
    override val pendingQueue: StateFlow<List<PendingQueueItem>> = _pendingQueue.asStateFlow()

    /** A cast request waiting for a device to connect. */
    private sealed interface CastRequest {
        val startPositionMs: Long?

        data class Remote(
            val video: DetectedVideo,
            val variant: VideoVariant?,
            val subtitle: SubtitleTrack?,
            val direct: Boolean,
            override val startPositionMs: Long?,
        ) : CastRequest

        data class Local(val uri: Uri, val title: String?, val mimeType: String?) : CastRequest {
            override val startPositionMs: Long? get() = null
        }
    }

    /** Last known position of the item playing on the receiver. */
    private data class Progress(val url: String, val positionMs: Long, val durationMs: Long, val isLive: Boolean)

    /** A failure carrying the user-facing result. */
    private class CastFailure(val kind: CastErrorKind, override val message: String) : Exception(message)

    // Main-thread state.
    private var pendingRequest: CastRequest? = null
    private var pendingExpiry: Job? = null
    private var loadInFlight = false
    private var suppressReceiverErrorsUntil = 0L
    private var lastProgress: Progress? = null
    private var finishedUrl: String? = null
    private var lastReceiverErrorKey: String? = null
    private var serviceRequested = false
    private val privateUrls = HashSet<String>()

    private val settings: AppSettings get() = settingsRepository.settings.value

    init {
        scope.launch(Dispatchers.Main.immediate) { observeConnection() }
        scope.launch(Dispatchers.Main.immediate) { observePlayback() }
        scope.launch(Dispatchers.Main.immediate) { savePositionsWhilePlaying() }
    }

    // region Casting

    override suspend fun cast(
        video: DetectedVideo,
        variant: VideoVariant?,
        subtitle: SubtitleTrack?,
        direct: Boolean,
        startPositionMs: Long?,
    ): CastResult = withContext(Dispatchers.Main.immediate) {
        if (video.isDrmProtected) return@withContext failure(CastErrorKind.DRM, R.string.cast_error_drm)
        if (castContextProvider.await() == null) {
            return@withContext failure(CastErrorKind.UNAVAILABLE, R.string.cast_error_unavailable)
        }
        if (usesProxy(direct) && networkHelper.lanIpv4() == null) {
            return@withContext failure(CastErrorKind.NO_WIFI, R.string.cast_error_no_wifi)
        }
        val request = CastRequest.Remote(video, variant, subtitle, direct, startPositionMs)
        if (chromecast.connectionState.value != CastConnectionState.CONNECTED) return@withContext defer(request)
        execute(request)
    }

    override suspend fun castLocalFile(uri: Uri, title: String?, mimeType: String?): CastResult =
        withContext(Dispatchers.Main.immediate) {
            if (castContextProvider.await() == null) {
                return@withContext failure(CastErrorKind.UNAVAILABLE, R.string.cast_error_unavailable)
            }
            if (networkHelper.lanIpv4() == null) {
                return@withContext failure(CastErrorKind.NO_WIFI, R.string.cast_error_no_wifi)
            }
            withContext(Dispatchers.IO) { localMedia.keepAccess(uri) }
            val request = CastRequest.Local(uri, title, mimeType)
            if (chromecast.connectionState.value != CastConnectionState.CONNECTED) return@withContext defer(request)
            execute(request)
        }

    override suspend fun addToQueue(video: DetectedVideo, playNext: Boolean): CastResult =
        withContext(Dispatchers.Main.immediate) {
            if (video.isDrmProtected) return@withContext failure(CastErrorKind.DRM, R.string.cast_error_drm)
            val casting = chromecast.connectionState.value == CastConnectionState.CONNECTED &&
                chromecast.playback.value != null
            if (!casting) {
                _pendingQueue.update { queue ->
                    val item = PendingQueueItem(PendingQueue.newKey(video, System.currentTimeMillis(), queue), video)
                    PendingQueue.add(queue, item, playNext)
                }
                _events.tryEmit(CastEvent.Queued(video.displayTitle))
                return@withContext CastResult.Success
            }
            proxyManager.acquire()
            try {
                val media = buildRemote(video, variant = null, subtitle = null, direct = false)
                val result = if (playNext) chromecast.insertNext(media) else chromecast.appendToQueue(listOf(media))
                if (result.isFailure) {
                    Log.w(TAG, "Queueing failed", result.exceptionOrNull())
                    return@withContext failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_queue)
                }
                if (video.isPrivate) privateUrls += video.url
                _events.tryEmit(CastEvent.Queued(media.title))
                CastResult.Success
            } catch (failure: CastFailure) {
                CastResult.Error(failure.kind, failure.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w(TAG, "Queueing failed", e)
                failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_queue)
            } finally {
                proxyManager.release()
            }
        }

    override fun removePending(key: String) {
        _pendingQueue.update { PendingQueue.remove(it, key) }
    }

    override fun movePending(key: String, toIndex: Int) {
        _pendingQueue.update { PendingQueue.move(it, key, toIndex) }
    }

    override suspend fun loadLocalSubtitle(uri: Uri): CastResult = withContext(Dispatchers.Main.immediate) {
        val state = chromecast.playback.value
        val current = if (state != null && chromecast.connectionState.value == CastConnectionState.CONNECTED) {
            chromecast.currentMedia()
        } else {
            null
        }
        if (state == null || current == null) {
            return@withContext failure(CastErrorKind.UNSUPPORTED, R.string.cast_error_nothing_casting)
        }
        proxyManager.acquire()
        try {
            val fileName = withContext(Dispatchers.IO) { localMedia.displayName(uri) }
            val vtt = withContext(Dispatchers.IO) { readSubtitleAsVtt(uri, SubtitleFormat.UNKNOWN, fileName) }
            val proxy = lanProxy()
            val registered = registerSafely { proxy.registerSubtitle(SubtitleSource.Inline(vtt, SubtitleFormat.VTT)) }
            val trackId = CastMediaRules.nextTrackId(current.textTracks.map { it.trackId })
            val track = CastSubtitle(
                trackId = trackId,
                url = proxy.url(registered.path),
                language = null,
                name = SubtitleText.trackName(fileName, string(R.string.cast_local_subtitle_name)),
            )
            val media = current.copy(textTracks = current.textTracks + track, activeTextTrackId = trackId)
            val positionMs = chromecast.playback.value?.positionMs ?: state.positionMs
            val result = chromecast.replaceCurrent(
                media,
                positionMs,
                autoplay = state.playerState != RemotePlayerState.PAUSED,
            )
            if (result.isFailure) {
                Log.w(TAG, "Reloading with subtitles failed", result.exceptionOrNull())
                failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_load_failed)
            } else {
                CastResult.Success
            }
        } catch (failure: CastFailure) {
            CastResult.Error(failure.kind, failure.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Reloading with subtitles failed", e)
            failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_load_failed)
        } finally {
            proxyManager.release()
        }
    }

    override suspend fun savedPositionMs(originalUrl: String): Long? = try {
        positions.get(originalUrl)?.positionMs
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Reading the saved position failed", e)
        null
    }

    override fun canCastDirectly(video: DetectedVideo): Boolean = CastMediaRules.canCastDirectly(video)

    // endregion

    // region Cast button and device chooser

    override fun createCastButton(context: Context): View {
        val state = castContextProvider.state.value
        if (state == CastContextProvider.State.Unavailable) return hiddenView(context)
        val button = try {
            MediaRouteButton(context)
        } catch (e: RuntimeException) {
            // MediaRouteButton needs an AppCompat-themed context.
            Log.w(TAG, "Cast button unavailable in this context", e)
            return hiddenView(context)
        }
        if (state is CastContextProvider.State.Ready) {
            setUpCastButton(context, button)
        } else {
            button.visibility = View.GONE
            scope.launch(Dispatchers.Main.immediate) {
                if (castContextProvider.await() != null && setUpCastButton(context, button)) {
                    button.visibility = View.VISIBLE
                }
            }
        }
        return button
    }

    private fun hiddenView(context: Context): View = View(context).apply { visibility = View.GONE }

    override fun showDeviceChooser(activity: FragmentActivity) {
        val castContext = castContextProvider.castContextOrNull
        if (castContext != null) {
            showRouteDialog(activity, castContext)
            return
        }
        if (castContextProvider.state.value == CastContextProvider.State.Unavailable) return
        scope.launch(Dispatchers.Main.immediate) {
            val ready = castContextProvider.await() ?: return@launch
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) showRouteDialog(activity, ready)
        }
    }

    private fun setUpCastButton(context: Context, button: MediaRouteButton): Boolean = try {
        CastButtonFactory.setUpMediaRouteButton(context, button)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Cast button setup failed", e)
        button.visibility = View.GONE
        false
    }

    /** The chooser while disconnected; the route controller (volume, stop casting) while connected. */
    private fun showRouteDialog(activity: FragmentActivity, castContext: CastContext) {
        val fragmentManager = activity.supportFragmentManager
        if (fragmentManager.isStateSaved || fragmentManager.findFragmentByTag(ROUTE_DIALOG_TAG) != null) return
        val factory = MediaRouteDialogFactory.getDefault()
        if (chromecast.connectionState.value == CastConnectionState.CONNECTED) {
            factory.onCreateControllerDialogFragment().show(fragmentManager, ROUTE_DIALOG_TAG)
            return
        }
        val selector = castContext.mergedSelector ?: return
        val chooser = factory.onCreateChooserDialogFragment()
        chooser.setRouteSelector(selector)
        chooser.show(fragmentManager, ROUTE_DIALOG_TAG)
    }

    // endregion

    // region Request execution

    private fun defer(request: CastRequest): CastResult {
        pendingRequest = request
        pendingExpiry?.cancel()
        pendingExpiry = scope.launch(Dispatchers.Main.immediate) {
            delay(PENDING_REQUEST_TTL_MS)
            pendingRequest = null
        }
        return CastResult.NeedsDevice
    }

    /** Builds the media (plus the pending queue) and loads it on the connected receiver. */
    private suspend fun execute(request: CastRequest): CastResult {
        proxyManager.acquire()
        loadInFlight = true
        try {
            val first = when (request) {
                is CastRequest.Remote -> buildRemote(request.video, request.variant, request.subtitle, request.direct)
                is CastRequest.Local -> buildLocal(request)
            }
            val queued = buildPendingQueue()
            val startMs = startPosition(first, request.startPositionMs)
            val result = chromecast.load(listOf(first) + queued.map { it.second }, 0, startMs, autoplay = true)
            if (result.isFailure) {
                Log.w(TAG, "Load failed", result.exceptionOrNull())
                suppressReceiverErrorsUntil = SystemClock.elapsedRealtime() + RECEIVER_ERROR_SUPPRESS_MS
                return failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_load_failed)
            }
            val consumed = queued.mapTo(HashSet()) { it.first.key }
            _pendingQueue.update { queue -> queue.filterNot { it.key in consumed } }
            if (request is CastRequest.Remote && request.video.isPrivate) privateUrls += request.video.url
            queued.forEach { (item, _) -> if (item.video.isPrivate) privateUrls += item.video.url }
            lastReceiverErrorKey = null
            _events.tryEmit(CastEvent.Started(first.title))
            startPlaybackService()
            return CastResult.Success
        } catch (failure: CastFailure) {
            return CastResult.Error(failure.kind, failure.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Load failed", e)
            return failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_load_failed)
        } finally {
            loadInFlight = false
            proxyManager.release()
        }
    }

    /** Pending items that can be built; the others stay in the app-side queue. */
    private suspend fun buildPendingQueue(): List<Pair<PendingQueueItem, CastMedia>> {
        val built = ArrayList<Pair<PendingQueueItem, CastMedia>>()
        for (item in _pendingQueue.value) {
            if (item.video.isDrmProtected) continue
            try {
                built += item to buildRemote(item.video, variant = null, subtitle = null, direct = false)
            } catch (failure: CastFailure) {
                Log.w(TAG, "Skipping queued video: ${failure.message}")
            }
        }
        return built
    }

    private fun usesProxy(direct: Boolean): Boolean = settings.proxyEnabled && !direct

    private suspend fun buildRemote(
        video: DetectedVideo,
        variant: VideoVariant?,
        subtitle: SubtitleTrack?,
        direct: Boolean,
    ): CastMedia {
        if (!usesProxy(direct)) return CastMediaFactory.direct(video, variant, subtitle)
        val proxy = lanProxy()
        val localSubtitles = readLocalSubtitles(CastMediaFactory.subtitleCandidates(video, subtitle))
        return registerSafely { CastMediaFactory.proxied(video, variant, subtitle, proxy, localSubtitles) }
    }

    private suspend fun buildLocal(request: CastRequest.Local): CastMedia {
        val proxy = lanProxy()
        val file = try {
            withContext(Dispatchers.IO) { localMedia.openVideo(request.uri, request.mimeType) }
        } catch (e: IOException) {
            Log.w(TAG, "Opening the local video failed", e)
            throw CastFailure(CastErrorKind.LOAD_FAILED, string(R.string.cast_error_local_file))
        }
        val registered = registerSafely { proxy.registerLocal(file.source, file.displayName ?: LOCAL_FILE_NAME) }
        return CastMedia(
            contentUrl = proxy.url(registered.path),
            contentType = file.source.mimeType,
            isLive = false,
            title = request.title?.takeIf { it.isNotBlank() }
                ?: file.displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: string(R.string.cast_local_file_title),
            subtitle = string(R.string.cast_local_file_subtitle),
            posterUrl = null,
            durationMs = file.durationMs,
            originalUrl = request.uri.toString(),
        )
    }

    /** The running proxy bound to the phone's LAN address. */
    private suspend fun lanProxy(): MediaProxy {
        val host = networkHelper.lanIpv4()
            ?: throw CastFailure(CastErrorKind.NO_WIFI, string(R.string.cast_error_no_wifi))
        val proxy = try {
            proxyManager.proxy()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Starting the proxy failed", e)
            throw CastFailure(CastErrorKind.PROXY_FAILED, string(R.string.cast_error_proxy))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Starting the proxy failed", e)
            throw CastFailure(CastErrorKind.PROXY_FAILED, string(R.string.cast_error_proxy))
        }
        return LanMediaProxy(proxy, host)
    }

    /** Runs proxy registrations off the main thread, mapping failures to [CastErrorKind.PROXY_FAILED]. */
    private suspend fun <T> registerSafely(block: () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        Log.w(TAG, "Proxy registration failed", e)
        throw CastFailure(CastErrorKind.PROXY_FAILED, string(R.string.cast_error_proxy))
    }

    /** Subtitles stored on the phone (content:// or file://), read and converted to WebVTT. */
    private suspend fun readLocalSubtitles(tracks: List<SubtitleTrack>): Map<String, SubtitleSource> {
        val local = tracks.filter { it.isLocal || !UrlUtils.isHttp(it.url) }
        if (local.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            local.mapNotNull { track ->
                try {
                    val uri = Uri.parse(track.url)
                    val vtt = readSubtitleAsVtt(uri, track.format, localMedia.displayName(uri))
                    track.id to SubtitleSource.Inline(vtt, SubtitleFormat.VTT)
                } catch (failure: CastFailure) {
                    Log.w(TAG, "Skipping local subtitle: ${failure.message}")
                    null
                }
            }.toMap()
        }
    }

    /** Blocking: reads a subtitle file and returns it as WebVTT. */
    private fun readSubtitleAsVtt(uri: Uri, declared: SubtitleFormat, fileName: String?): String {
        val text = try {
            SubtitleText.decode(localMedia.readBytes(uri, SubtitleText.MAX_BYTES))
        } catch (e: IOException) {
            throw CastFailure(CastErrorKind.LOAD_FAILED, string(R.string.cast_error_subtitle_read))
        }
        return try {
            val sniffed = SubtitleConverter.sniffFormat(text)
            val candidates = listOf(sniffed, declared, SubtitleText.formatFromFileName(fileName))
            val format = candidates.firstOrNull { it != SubtitleFormat.UNKNOWN }
                ?: throw CastFailure(CastErrorKind.UNSUPPORTED, string(R.string.cast_error_subtitle_format))
            SubtitleConverter.toVtt(text, format)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Converting the subtitle failed", e)
            throw CastFailure(CastErrorKind.UNSUPPORTED, string(R.string.cast_error_subtitle_format))
        }
    }

    private suspend fun startPosition(media: CastMedia, requestedMs: Long?): Long {
        val resume = settings.resumePlayback
        val saved = if (ResumePolicy.shouldLookUp(requestedMs, resume, media.isLive)) {
            savedPositionMs(media.originalUrl)
        } else {
            null
        }
        return ResumePolicy.startPositionMs(requestedMs, resume, media.isLive, saved)
    }

    private fun startPlaybackService() {
        serviceRequested = true
        try {
            ContextCompat.startForegroundService(context, Intent(context, CastPlaybackService::class.java))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses foreground services started from the background.
            Log.w(TAG, "Couldn't start the casting service", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't start the casting service", e)
        }
    }

    // endregion

    // region Observers (main thread)

    private suspend fun observeConnection() {
        var wasConnected = false
        chromecast.connectionState.collect { state ->
            when (state) {
                CastConnectionState.CONNECTED -> {
                    wasConnected = true
                    val request = pendingRequest
                    if (request != null) {
                        pendingRequest = null
                        pendingExpiry?.cancel()
                        scope.launch(Dispatchers.Main.immediate) { runPendingRequest(request) }
                    }
                }
                CastConnectionState.CONNECTING -> Unit
                CastConnectionState.NOT_CONNECTED,
                CastConnectionState.NO_DEVICES,
                CastConnectionState.UNAVAILABLE,
                -> if (wasConnected) {
                    wasConnected = false
                    serviceRequested = false
                    lastProgress?.let(::persist)
                    lastProgress = null
                    _events.tryEmit(CastEvent.SessionEnded)
                }
            }
        }
    }

    private suspend fun runPendingRequest(request: CastRequest) {
        val result = execute(request)
        if (result is CastResult.Error) _events.tryEmit(CastEvent.Error(result.kind, result.message))
    }

    private suspend fun observePlayback() {
        chromecast.playback.collect { state ->
            trackProgress(state)
            reportReceiverError(state)
            ensureServiceForResumedSession(state)
        }
    }

    /** Remembers the playing item's position; saves it when the item changes and clears it when finished. */
    private fun trackProgress(state: RemotePlaybackState?) {
        val url = state?.originalUrl
        val previous = lastProgress
        if (previous != null && previous.url != url) {
            persist(previous)
            lastProgress = null
        }
        if (state == null || url == null) return
        if (state.idleReason == IDLE_REASON_FINISHED) {
            lastProgress = null
            if (finishedUrl != url) {
                finishedUrl = url
                clearPosition(url)
            }
            return
        }
        if (state.playerState in ACTIVE_STATES && state.positionMs > 0) {
            if (finishedUrl == url) finishedUrl = null
            lastProgress = Progress(url, state.positionMs, state.durationMs, state.isLive)
        }
    }

    private fun reportReceiverError(state: RemotePlaybackState?) {
        val current = state ?: return
        if (current.idleReason != IDLE_REASON_ERROR || loadInFlight) return
        if (SystemClock.elapsedRealtime() < suppressReceiverErrorsUntil) return
        val key = "${current.currentItemId}:${current.originalUrl}"
        if (key == lastReceiverErrorKey) return
        lastReceiverErrorKey = key
        _events.tryEmit(CastEvent.Error(CastErrorKind.LOAD_FAILED, string(R.string.cast_error_playback)))
    }

    /** A session resumed after an app restart gets its notification back while the app is visible. */
    private fun ensureServiceForResumedSession(state: RemotePlaybackState?) {
        if (state == null || serviceRequested) return
        if (chromecast.connectionState.value != CastConnectionState.CONNECTED) return
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        startPlaybackService()
    }

    /** Saves the position every few seconds while playing, and once when playback pauses or stops. */
    private suspend fun savePositionsWhilePlaying() {
        chromecast.playback
            .map { it?.playerState == RemotePlayerState.PLAYING }
            .distinctUntilChanged()
            .collectLatest { playing ->
                if (!playing) {
                    lastProgress?.let(::persist)
                    return@collectLatest
                }
                while (true) {
                    delay(SAVE_INTERVAL_MS)
                    lastProgress?.let(::persist)
                }
            }
    }

    private fun persist(progress: Progress) {
        val save = ResumePolicy.shouldSave(
            resumeEnabled = settings.resumePlayback,
            isLive = progress.isLive,
            isPrivate = progress.url in privateUrls,
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
        )
        if (!save) return
        scope.launch {
            try {
                positions.save(progress.url, progress.positionMs, progress.durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving the position failed", e)
            }
        }
    }

    private fun clearPosition(url: String) {
        scope.launch {
            try {
                positions.clear(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Clearing the position failed", e)
            }
        }
    }

    // endregion

    private fun failure(kind: CastErrorKind, @StringRes message: Int): CastResult.Error =
        CastResult.Error(kind, string(message))

    private fun string(@StringRes id: Int): String = context.getString(id)

    private companion object {
        const val TAG = "SnapcastCast"
        const val EVENT_BUFFER = 16
        const val ROUTE_DIALOG_TAG = "snapcast_cast_route_dialog"
        const val LOCAL_FILE_NAME = "video"
        const val PENDING_REQUEST_TTL_MS = 2 * 60_000L
        const val SAVE_INTERVAL_MS = 5_000L
        const val RECEIVER_ERROR_SUPPRESS_MS = 5_000L
        const val IDLE_REASON_FINISHED = "FINISHED"
        const val IDLE_REASON_ERROR = "ERROR"
        val ACTIVE_STATES = setOf(RemotePlayerState.PLAYING, RemotePlayerState.PAUSED, RemotePlayerState.BUFFERING)
    }
}
