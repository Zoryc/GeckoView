package app.snapcast.ui.cast

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.CastController
import app.snapcast.cast.CastDeviceInfo
import app.snapcast.cast.CastResult
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.ui.R
import app.snapcast.ui.shared.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the expanded controller shows. */
internal data class CastScreenState(
    val connection: CastConnectionState = CastConnectionState.NOT_CONNECTED,
    val device: CastDeviceInfo? = null,
    /** Null when nothing is loaded on the receiver. */
    val playback: RemotePlaybackState? = null,
)

/** Expanded cast controller: transport, volume, speed and subtitle controls for the active receiver. */
@HiltViewModel
internal class CastControllerViewModel @Inject constructor(
    private val controller: CastController,
) : ViewModel() {

    private val target get() = controller.target
    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)

    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<CastScreenState> =
        combine(controller.connectionState, controller.target.device, controller.playback) { connection, device, playback ->
            CastScreenState(connection, device, playback)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = CastScreenState(
                controller.connectionState.value,
                controller.target.device.value,
                controller.playback.value,
            ),
        )

    fun togglePlayPause() = target.togglePlayPause()

    fun seekTo(positionMs: Long) = target.seekTo(positionMs.coerceAtLeast(0))

    fun rewind() = target.seekBy(-CastControls.SEEK_STEP_MS)

    fun fastForward() = target.seekBy(CastControls.SEEK_STEP_MS)

    fun skipNext() = target.skipNext()

    fun skipPrevious() = target.skipPrevious()

    /** Stops playback on the receiver (the cast session stays connected). */
    fun stop() = target.stop()

    fun setVolume(level: Float) = target.setVolume(level.coerceIn(0f, 1f).toDouble())

    fun setMuted(muted: Boolean) = target.setMuted(muted)

    fun setPlaybackRate(rate: Double) = target.setPlaybackRate(rate)

    /** Enables the text track [trackId], or turns subtitles off with null. */
    fun setTextTrack(trackId: Long?) = target.setActiveTextTrack(trackId)

    fun loadSubtitle(uri: Uri) {
        viewModelScope.launch {
            when (val result = controller.loadLocalSubtitle(uri)) {
                CastResult.Success -> messageChannel.trySend(UiMessage.res(R.string.ui_screens_cast_subtitle_loaded))
                CastResult.NeedsDevice -> messageChannel.trySend(UiMessage.res(R.string.ui_screens_cast_nothing_casting))
                is CastResult.Error -> messageChannel.trySend(UiMessage.Text(result.message))
            }
        }
    }

    /** Ends the cast session and stops playback on the receiver. */
    fun disconnect() = target.disconnect(stopCasting = true)

    fun createCastButton(context: Context): View = controller.createCastButton(context)

    fun showDeviceChooser(activity: FragmentActivity) = controller.showDeviceChooser(activity)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
