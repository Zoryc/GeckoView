package app.snapcast.cast

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.fragment.app.FragmentActivity
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class CastErrorKind {
    /** Stream is DRM-protected. */
    DRM,

    /** Phone is not on Wi-Fi (the receiver could not reach the proxy). */
    NO_WIFI,

    /** Cast framework unavailable (no Google Play services). */
    UNAVAILABLE,

    PROXY_FAILED,
    LOAD_FAILED,
    UNSUPPORTED,
}

sealed interface CastResult {
    data object Success : CastResult

    /**
     * Not connected to a device. The request is kept and runs automatically once a device connects;
     * the UI should open the device chooser ([CastController.showDeviceChooser]).
     */
    data object NeedsDevice : CastResult

    data class Error(val kind: CastErrorKind, val message: String) : CastResult
}

sealed interface CastEvent {
    data class Error(val kind: CastErrorKind, val message: String) : CastEvent

    data class Started(val title: String) : CastEvent

    data class Queued(val title: String) : CastEvent

    data object SessionEnded : CastEvent
}

/** Item waiting in the app-side queue while nothing is casting. */
data class PendingQueueItem(
    val key: String,
    val video: DetectedVideo,
)

/** UI-facing facade over the active [CastTarget], the local proxy and the casting foreground service. */
interface CastController {
    val target: CastTarget

    val connectionState: StateFlow<CastConnectionState>

    val playback: StateFlow<RemotePlaybackState?>

    val events: SharedFlow<CastEvent>

    /** App-side queue used while not casting; sent to the receiver when casting starts. */
    val pendingQueue: StateFlow<List<PendingQueueItem>>

    /**
     * Casts [video]. Uses the proxy (with the captured headers and playlist rewriting) unless [direct] is
     * true or the proxy is disabled in settings. [variant] picks one HLS variant playlist instead of the
     * master (ignored when its audio is a separate rendition, which only the master carries).
     * [startPositionMs] null resumes from the saved position when enabled in settings.
     */
    suspend fun cast(
        video: DetectedVideo,
        variant: VideoVariant? = null,
        subtitle: SubtitleTrack? = null,
        direct: Boolean = false,
        startPositionMs: Long? = null,
    ): CastResult

    /** Appends to the receiver queue when casting, otherwise to [pendingQueue]. [playNext] inserts after the current item. */
    suspend fun addToQueue(video: DetectedVideo, playNext: Boolean = false): CastResult

    /**
     * Casts [pendingQueue] in order. Items leave the pending queue only once the receiver has loaded them;
     * while no device is connected the request waits for one ([CastResult.NeedsDevice]), and an expired
     * request or a failed load leave the pending queue untouched.
     */
    suspend fun castPendingQueue(): CastResult

    fun removePending(key: String)

    fun movePending(key: String, toIndex: Int)

    /** Casts a video stored on the phone (content:// URI) through the proxy. */
    suspend fun castLocalFile(uri: Uri, title: String?, mimeType: String?): CastResult

    /** Loads a user-picked .srt/.vtt file and enables it on the current receiver item. */
    suspend fun loadLocalSubtitle(uri: Uri): CastResult

    /** Saved resume position for [originalUrl], if any. */
    suspend fun savedPositionMs(originalUrl: String): Long?

    /** A MediaRouteButton wired to the Cast framework (the activity must be a FragmentActivity with an AppCompat theme). */
    fun createCastButton(context: Context): View

    /** Opens the device chooser dialog. */
    fun showDeviceChooser(activity: FragmentActivity)

    /** False when the stream needs headers (cookies / referer) that the receiver cannot send. */
    fun canCastDirectly(video: DetectedVideo): Boolean
}
