package app.snapcast.detection

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.snapcast.core.detection.DetectionFilter
import app.snapcast.core.detection.DetectorMessage
import app.snapcast.core.detection.DetectorProtocol
import app.snapcast.core.detection.DrmMessage
import app.snapcast.core.detection.HelloMessage
import app.snapcast.core.detection.MediaMessage
import app.snapcast.core.detection.MediaReport
import app.snapcast.core.detection.PageMessage
import app.snapcast.core.detection.NativeCommand
import app.snapcast.core.detection.RescanCommand
import app.snapcast.core.detection.ThumbnailsCommand
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DrmInfo
import app.snapcast.data.di.ApplicationScope
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.detection.extension.DetectorMessageSink
import app.snapcast.detection.inspect.InspectionResult
import app.snapcast.detection.inspect.StreamInspector
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * Per-tab video lists fed by the detector extension. One [app.snapcast.core.detection.VideoCollector]
 * per tab, all touched on the main thread only (where GeckoView delivers port messages and session
 * callbacks); manifests and file sizes are fetched on [Dispatchers.IO] by the [StreamInspector] and
 * applied back on the main thread. Lists are published raw and filtered with the current settings.
 */
@Singleton
internal class DetectedVideoRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val inspector: StreamInspector,
    @ApplicationScope private val scope: CoroutineScope,
) : DetectedVideoRepository, DetectorMessageSink {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clock: () -> Long = System::currentTimeMillis
    private val tabs = LinkedHashMap<String, TabDetection>()
    private val announcer = NewVideoTracker()
    private val inspectionPermits = Semaphore(MAX_PARALLEL_INSPECTIONS)

    private val rawByTab = MutableStateFlow<Map<String, List<DetectedVideo>>>(emptyMap())
    private val visibleByTab = MutableStateFlow<Map<String, List<DetectedVideo>>>(emptyMap())
    private val announcements = MutableSharedFlow<DetectedVideo>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val ready = MutableStateFlow(false)

    override val videosByTab: StateFlow<Map<String, List<DetectedVideo>>> = visibleByTab.asStateFlow()
    override val newVideos: SharedFlow<DetectedVideo> = announcements.asSharedFlow()
    override val isDetectorReady: StateFlow<Boolean> = ready.asStateFlow()

    init {
        // Setting changes (minimum size, ignored domains) apply to the lists right away.
        scope.launch(Dispatchers.Main.immediate) {
            settingsRepository.settings.collect { publish() }
        }
    }

    override fun videosForTab(tabId: String): Flow<List<DetectedVideo>> =
        visibleByTab.map { it[tabId].orEmpty() }.distinctUntilChanged()

    override fun findVideo(tabId: String, videoId: String): DetectedVideo? =
        rawByTab.value[tabId]?.firstOrNull { it.id == videoId }

    override fun requestRescan(tabId: String) = onMain {
        tabs[tabId]?.rescanPort()?.let(::postRescan)
    }

    override fun requestThumbnails(tabId: String) = onMain {
        tabs[tabId]?.rescanPort()?.let { post(it, ThumbnailsCommand) }
    }

    override fun clearTab(tabId: String) = onMain {
        val tab = tabs[tabId] ?: return@onMain
        tab.resetDocument(tab.collector.pageUrl)
        announcer.resetTab(tabId)
        publish()
    }

    // Session lifecycle, from DetectionSessionObserver and DetectorExtensionManager (main thread).

    fun setDetectorReady(isReady: Boolean) {
        ready.value = isReady
    }

    /** The only place a tab's detection state is created: events for unknown (closed) tabs are dropped. */
    fun onTabOpened(tabId: String, isPrivate: Boolean) {
        tabs.getOrPut(tabId) { TabDetection(tabId, isPrivate, clock) }
    }

    fun onTabClosed(tabId: String) {
        tabs.remove(tabId) ?: return
        announcer.resetTab(tabId)
        publish()
    }

    fun onPageStart(tabId: String, url: String) {
        val tab = tabs[tabId] ?: return
        tab.startDocument(url)
        announcer.resetTab(tabId)
        publish()
    }

    fun onLocationChange(tabId: String, url: String) {
        val tab = tabs[tabId] ?: return
        if (tab.onLocationChange(url) && DocumentUrls.isDirectMedia(url)) confirmDirectMedia(tab, url)
    }

    fun markDrm(tabId: String, info: DrmInfo) {
        val tab = tabs[tabId] ?: return
        tab.collector.markDrm(info)
        publish()
    }

    fun onMediaTitle(tabId: String, title: String?) {
        val tab = tabs[tabId] ?: return
        tab.mediaTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: return
        applyMediaTitle(tab)
        publish()
    }

    // DetectorMessageSink (main thread).

    override fun onPortConnected(tabId: String, isPrivate: Boolean, port: WebExtension.Port) {
        // Unknown tab: already closed, a late connect from its dying session. Re-creating its state here
        // would keep it (private-tab URLs and cookies included) for the life of the process.
        val tab = tabs[tabId] ?: return
        tab.onPortConnected(port)
        // Answering right away tells the content script that the app listens: it then replays what it
        // posted before this delegate was attached. The rescan also catches elements found meanwhile.
        mainHandler.post {
            if (tabs[tabId]?.port === port) postRescan(port)
        }
    }

    override fun onPortDisconnected(tabId: String, port: WebExtension.Port) {
        tabs[tabId]?.onPortDisconnected(port)
    }

    override fun onDetectorMessage(tabId: String, isPrivate: Boolean, message: DetectorMessage) {
        val tab = tabs[tabId] ?: return // A late message after onTabClosed.
        when (message) {
            is HelloMessage -> if (DocumentUrls.isNewDocument(message.url, tab.collector.pageUrl, tab.lastLocation)) {
                tab.resetDocument(message.url)
                announcer.resetTab(tabId)
            }
            is PageMessage -> tab.collector.onPage(message)
            is MediaMessage -> addReports(tab, message.items)
            is DrmMessage -> tab.collector.markDrm(DrmInfo(system = message.keySystem, reason = DRM_REASON_ENCRYPTED))
        }
        applyMediaTitle(tab)
        publish()
    }

    private fun addReports(tab: TabDetection, reports: List<MediaReport>) {
        for (report in reports) {
            val video = tab.collector.add(report) ?: continue
            inspectIfNeeded(tab, video)
        }
    }

    /** Media session titles name what is playing; they replace missing titles and page-title fallbacks. */
    private fun applyMediaTitle(tab: TabDetection) {
        val title = tab.mediaTitle ?: return
        for (video in tab.collector.videos) {
            if (video.title.isNullOrBlank() || video.title == video.pageTitle) {
                tab.collector.update(video.id) { it.copy(title = title) }
            }
        }
    }

    /**
     * The top-level document's URL looks like a stream: it is listed once a probe (off the main thread)
     * confirms it is media. Pages such as https://commons.wikimedia.org/wiki/File:Movie.webm are HTML.
     */
    private fun confirmDirectMedia(tab: TabDetection, url: String) {
        val known = tab.collector.videos
        if (DocumentUrls.isListed(url, known)) return
        val tabId = tab.tabId
        val generation = tab.generation
        val target = DocumentUrls.probeTarget(url, tabId, tab.isPrivate, known)
        scope.launch(Dispatchers.IO) {
            val confirmed = try {
                inspectionPermits.withPermit { inspector.confirmDirectMedia(target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Probe of $url failed", e)
                null
            }
            if (confirmed == null) return@launch
            withContext(Dispatchers.Main.immediate) {
                // Dropped when the tab closed (or was re-created after a crash) or moved on to another document.
                if (tabs[tabId] !== tab || tab.generation != generation) return@withContext
                addReports(tab, listOf(DocumentUrls.directMediaReport(url, clock(), confirmed.mimeType)))
                applyMediaTitle(tab)
                publish()
            }
        }
    }

    private fun inspectIfNeeded(tab: TabDetection, video: DetectedVideo) {
        if (!tab.inspections.shouldStart(video)) return
        val tabId = tab.tabId
        val generation = tab.generation
        scope.launch(Dispatchers.IO) {
            val result = try {
                inspectionPermits.withPermit { inspector.inspect(video) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Inspection of ${video.url} failed", e)
                null
            }
            withContext(Dispatchers.Main.immediate) {
                onInspected(tabId, generation, video.id, result)
            }
        }
    }

    private fun onInspected(tabId: String, generation: Int, videoId: String, result: InspectionResult?) {
        val tab = tabs[tabId]?.takeIf { it.generation == generation } ?: return
        tab.inspections.finished(videoId, succeeded = result != null)
        if (result == null) return
        tab.collector.update(videoId) { result.applyTo(it) }
        if (result.childPlaylistUrls.isNotEmpty()) tab.collector.onMasterParsed(videoId, result.childPlaylistUrls)
        publish()
    }

    /** Publishes every tab's raw and visible lists and announces newly visible videos. */
    private fun publish() {
        val raw = LinkedHashMap<String, List<DetectedVideo>>(tabs.size)
        for ((tabId, tab) in tabs) raw[tabId] = tab.collector.videos
        val settings = settingsRepository.settings.value
        val visible = LinkedHashMap<String, List<DetectedVideo>>(raw.size)
        for ((tabId, videos) in raw) visible[tabId] = DetectionFilter.apply(videos, settings)
        rawByTab.value = raw
        visibleByTab.value = visible
        for (video in announcer.newlyVisible(visible)) announcements.tryEmit(video)
    }

    private fun postRescan(port: WebExtension.Port) = post(port, RescanCommand)

    private fun post(port: WebExtension.Port, command: NativeCommand) {
        try {
            port.postMessage(JSONObject(DetectorProtocol.encode(command)))
        } catch (e: JSONException) {
            Log.w(TAG, "Could not encode the $command command", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not post to the detector port", e)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "SnapcastDetection"
        const val DRM_REASON_ENCRYPTED = "EME (encrypted event)"
        const val MAX_PARALLEL_INSPECTIONS = 4
    }
}
