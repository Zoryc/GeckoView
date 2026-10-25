package app.snapcast.ui.cast

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.CastController
import app.snapcast.cast.CastQueueItem
import app.snapcast.cast.CastResult
import app.snapcast.cast.PendingQueueItem
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.ui.R
import app.snapcast.ui.shared.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The queue shown: the receiver's while casting, the app-side pending queue otherwise. */
internal sealed interface QueueUiState {
    data class Remote(val items: List<CastQueueItem>, val currentItemId: Int?) : QueueUiState

    data class Pending(val items: List<PendingQueueItem>) : QueueUiState
}

/** One-shot requests the queue screen carries out with the activity. */
internal sealed interface QueueEffect {
    /** Casting needs a device: open the Cast device chooser (the request runs once one connects). */
    data object ChooseDevice : QueueEffect

    data class Message(val message: UiMessage) : QueueEffect
}

@HiltViewModel
internal class QueueViewModel @Inject constructor(
    private val controller: CastController,
) : ViewModel() {

    private val target get() = controller.target
    private val effectChannel = Channel<QueueEffect>(Channel.BUFFERED)
    private val castingQueue = MutableStateFlow(false)

    val effects: Flow<QueueEffect> = effectChannel.receiveAsFlow()

    /** True while "Cast queue" is starting the first item. */
    val startingCast: StateFlow<Boolean> = castingQueue.asStateFlow()

    val uiState: StateFlow<QueueUiState> =
        combine(controller.connectionState, controller.playback, controller.pendingQueue, ::queueState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = queueState(
                    controller.connectionState.value,
                    controller.playback.value,
                    controller.pendingQueue.value,
                ),
            )

    // Receiver queue

    fun jumpTo(itemId: Int) = target.jumpToQueueItem(itemId)

    fun playNext(itemId: Int) {
        val state = uiState.value as? QueueUiState.Remote ?: return
        QueueMath.playNextIndex(state.items.map { it.itemId }, itemId, state.currentItemId)
            ?.let { target.moveQueueItem(itemId, it) }
    }

    fun moveRemote(itemId: Int, delta: Int) {
        val state = uiState.value as? QueueUiState.Remote ?: return
        val from = state.items.indexOfFirst { it.itemId == itemId }
        QueueMath.shiftedIndex(state.items.size, from, delta)?.let { target.moveQueueItem(itemId, it) }
    }

    fun removeRemote(itemId: Int) = target.removeQueueItem(itemId)

    // Pending queue

    fun movePending(key: String, delta: Int) {
        val items = (uiState.value as? QueueUiState.Pending)?.items ?: return
        val from = items.indexOfFirst { it.key == key }
        QueueMath.shiftedIndex(items.size, from, delta)?.let { controller.movePending(key, it) }
    }

    fun removePending(key: String) = controller.removePending(key)

    /**
     * Casts the first pending item; [CastController.cast] then sends the rest of the pending queue to the
     * receiver. The first item is taken out of the pending queue so it is not loaded twice, and put back in
     * front if casting fails.
     */
    fun castQueue() {
        val first = (uiState.value as? QueueUiState.Pending)?.items?.firstOrNull() ?: return
        if (castingQueue.value) return
        castingQueue.value = true
        viewModelScope.launch {
            try {
                controller.removePending(first.key)
                when (val result = controller.cast(first.video)) {
                    CastResult.Success -> Unit
                    CastResult.NeedsDevice -> effectChannel.trySend(QueueEffect.ChooseDevice)
                    is CastResult.Error -> {
                        controller.addToQueue(first.video, playNext = true)
                        effectChannel.trySend(QueueEffect.Message(UiMessage.Text(result.message)))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w(TAG, "Casting the queue failed", e)
                effectChannel.trySend(QueueEffect.Message(UiMessage.res(R.string.ui_screens_cast_failed)))
            } finally {
                castingQueue.value = false
            }
        }
    }

    fun showDeviceChooser(activity: FragmentActivity) = controller.showDeviceChooser(activity)

    private fun queueState(
        connection: CastConnectionState,
        playback: RemotePlaybackState?,
        pending: List<PendingQueueItem>,
    ): QueueUiState = if (connection == CastConnectionState.CONNECTED && playback != null) {
        QueueUiState.Remote(playback.queue, playback.currentItemId)
    } else {
        QueueUiState.Pending(pending)
    }

    private companion object {
        const val TAG = "SnapcastQueue"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
