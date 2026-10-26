package app.snapcast.cast.controller

import app.snapcast.cast.RemotePlaybackState
import app.snapcast.cast.RemotePlayerState

/** Pure decisions about the queue on the receiver. */
internal object ReceiverQueue {
    const val IDLE_REASON_FINISHED = "FINISHED"
    const val IDLE_REASON_CANCELED = "CANCELED"
    const val IDLE_REASON_ERROR = "ERROR"

    /**
     * True when the receiver's queue is over, so an appended item would never play: playback was stopped
     * (CANCELED), or the last item finished or failed. IDLE without a reason and INTERRUPTED are brief
     * states while a load replaces the current item and do not count.
     */
    fun isEnded(state: RemotePlaybackState): Boolean {
        if (state.playerState != RemotePlayerState.IDLE) return false
        return when (state.idleReason) {
            IDLE_REASON_CANCELED -> true
            IDLE_REASON_FINISHED, IDLE_REASON_ERROR -> {
                val index = state.queue.indexOfFirst { it.itemId == state.currentItemId }
                index < 0 || index == state.queue.lastIndex
            }
            else -> false
        }
    }
}
