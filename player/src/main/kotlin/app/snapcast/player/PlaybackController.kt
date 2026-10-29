package app.snapcast.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import app.snapcast.data.playback.PlaybackPositionRepository
import app.snapcast.core.util.UrlUtils
import app.snapcast.data.diagnostics.DiagnosticsLog
import app.snapcast.player.error.PlaybackErrorMessages
import app.snapcast.player.error.PlayerErrorKind
import app.snapcast.player.error.PlayerErrorUi
import app.snapcast.player.media.PlayerFactory
import app.snapcast.player.media.QualityMatch
import app.snapcast.player.resume.PositionWriter
import app.snapcast.player.resume.StartPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Playback state shown by the player screen. */
internal data class PlaybackUiState(
    val title: String = "",
    val isPlaying: Boolean = false,
    /** Position playback resumed at; non-null while the "Resumed at … · Start over" notice is shown. */
    val resumedAtMs: Long? = null,
    val error: PlayerErrorUi? = null,
)

/**
 * Owns the ExoPlayer of the current [PlaybackRequest]: start position (explicit, restored or resumed),
 * periodic position saving, error reporting and recovery. Main thread only; [scope] is the activity's
 * lifecycle scope.
 */
internal class PlaybackController(
    private val context: Context,
    private val playerFactory: PlayerFactory,
    private val positionRepository: PlaybackPositionRepository,
    private val positionWriter: PositionWriter,
    private val resumeEnabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val diagnostics: DiagnosticsLog? = null,
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    var request: PlaybackRequest? = null
        private set

    /** Called after anything picture-in-picture parameters depend on changed (playing, video size, error). */
    var onPlaybackChanged: () -> Unit = {}

    private var startJob: Job? = null
    private var saveJob: Job? = null
    private var liveEdgeRecoveries = 0

    /** The picked quality still has to be applied (once the stream's renditions are known). */
    private var qualityPending = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) savePosition()
            onPlaybackChanged()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    liveEdgeRecoveries = 0
                    _state.update { it.copy(error = null) }
                }
                Player.STATE_ENDED -> {
                    request?.let { positionWriter.clear(it.url) }
                    _state.update { it.copy(resumedAtMs = null) }
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            onPlaybackChanged()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (qualityPending) applyQuality(tracks)
        }
    }

    /**
     * Replaces the current item (if any, its position is saved first) with [request]. [explicitStartMs]
     * (requested or restored) wins over the saved position.
     */
    fun open(request: PlaybackRequest, explicitStartMs: Long?, playWhenReady: Boolean) {
        release()
        this.request = request
        liveEdgeRecoveries = 0
        qualityPending = request.quality != null
        _state.value = PlaybackUiState(title = request.title)
        val player = playerFactory.createPlayer(context, request)
        player.addListener(listener)
        player.playWhenReady = playWhenReady
        _player.value = player

        startJob = scope.launch {
            val resume = resumeEnabled()
            val saved = if (StartPosition.needsSavedPosition(explicitStartMs, resume)) savedPositionOf(request.url) else null
            val start = StartPosition.decide(explicitStartMs, resume, saved)
            val item = playerFactory.createMediaItem(request)
            val position = start.positionMs
            if (position != null) player.setMediaItem(item, position) else player.setMediaItem(item)
            player.prepare()
            if (start.resumed) _state.update { it.copy(resumedAtMs = position) }
        }
        saveJob = scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                if (player.isPlaying) savePosition()
            }
        }
    }

    fun play() {
        val player = _player.value ?: return
        if (player.currentMediaItem != null) {
            when (player.playbackState) {
                Player.STATE_ENDED -> player.seekToDefaultPosition()
                Player.STATE_IDLE -> {
                    _state.update { it.copy(error = null) }
                    player.prepare()
                }
                else -> Unit
            }
        }
        player.play()
    }

    fun pause() {
        _player.value?.pause()
    }

    /** Re-prepares after an error; a stream that fell behind its live window restarts at the live edge. */
    fun retry() {
        val player = _player.value ?: return
        if (player.currentMediaItem == null) return
        if (player.playerError?.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
        }
        liveEdgeRecoveries = 0
        _state.update { it.copy(error = null) }
        player.prepare()
        player.play()
    }

    /** "Start over" on the resume notice: back to the beginning and forget the saved position. */
    fun startOver() {
        val player = _player.value ?: return
        player.seekTo(0L)
        request?.let { positionWriter.clear(it.url) }
        _state.update { it.copy(resumedAtMs = null) }
    }

    fun dismissResumeNotice() {
        _state.update { it.copy(resumedAtMs = null) }
    }

    /**
     * Hands the current position to the store (on-demand media with a known duration only). Nothing is
     * written for a private-tab request or while "Resume playback" is off.
     */
    fun savePosition() {
        val request = request ?: return
        if (!StartPosition.mayStore(resumeEnabled(), request.isPrivate)) return
        val player = _player.value ?: return
        if (player.currentMediaItem == null || player.playbackState == Player.STATE_ENDED) return
        val duration = player.duration
        if (!StartPosition.isPersistable(duration, player.isCurrentMediaItemLive)) return
        positionWriter.save(request.url, player.currentPosition, duration)
    }

    /** Saves the position and releases the player. */
    fun release() {
        startJob?.cancel()
        saveJob?.cancel()
        startJob = null
        saveJob = null
        savePosition()
        _player.value?.let {
            it.removeListener(listener)
            it.release()
        }
        _player.value = null
    }

    private fun handleError(error: PlaybackException) {
        val player = _player.value ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
            liveEdgeRecoveries < MAX_LIVE_EDGE_RECOVERIES
        ) {
            // The live window moved past the playback position: jump to the live edge and carry on.
            liveEdgeRecoveries++
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        val described = PlaybackErrorMessages.describe(context.resources, error)
        // A refused, vanished or replaced link usually expired: reloading the page it came from gets a fresh one.
        val pageUrl = request?.pageUrl
        val canReload = pageUrl != null && UrlUtils.isHttp(pageUrl) && described.kind in RELOADABLE
        _state.update { it.copy(error = described.copy(canReloadPage = canReload), resumedAtMs = null) }
        if (request?.isPrivate == false) {
            val host = described.failedHost ?: UrlUtils.hostOf(request?.url.orEmpty())
            val what = described.httpStatus?.let { "HTTP $it from $host" }
                ?: described.instead?.let { "$it instead of a playlist from $host" }
                ?: described.kind.name
            diagnostics?.record("player", "$what (${described.detail})", request?.pageUrl)
        }
        onPlaybackChanged()
    }

    /** Pins the video rendition closest to the picked quality, once; the player adapts again if none matches. */
    private fun applyQuality(tracks: Tracks) {
        val player = _player.value ?: return
        val wanted = request?.quality ?: return
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        qualityPending = false
        val supported = (0 until group.length).filter { group.isTrackSupported(it) }
        val renditions = supported.map { index ->
            val format = group.getTrackFormat(index)
            QualityMatch.Rendition(format.width, format.height, format.bitrate)
        }
        val choice = QualityMatch.pick(renditions, wanted) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, supported[choice]))
            .build()
    }

    private suspend fun savedPositionOf(url: String): Long? = try {
        positionRepository.get(url)?.positionMs
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L

        /** Consecutive automatic live-edge recoveries before the error is shown. */
        const val MAX_LIVE_EDGE_RECOVERIES = 3

        /** Failures a fresh link from the page can fix (an unreadable playlist is often an expired link's answer). */
        val RELOADABLE = setOf(
            PlayerErrorKind.HTTP_FORBIDDEN,
            PlayerErrorKind.HTTP_NOT_FOUND,
            PlayerErrorKind.NOT_A_PLAYLIST,
            PlayerErrorKind.MALFORMED,
        )
    }
}
