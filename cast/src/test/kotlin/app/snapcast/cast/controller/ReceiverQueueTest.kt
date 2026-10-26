package app.snapcast.cast.controller

import app.snapcast.cast.CastQueueItem
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.cast.RemotePlayerState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReceiverQueueTest {

    private fun item(id: Int) = CastQueueItem(itemId = id, title = "Item $id", subtitle = null, posterUrl = null, originalUrl = null)

    private fun state(
        playerState: RemotePlayerState,
        idleReason: String? = null,
        queue: List<CastQueueItem> = listOf(item(1), item(2)),
        currentItemId: Int? = 2,
    ) = RemotePlaybackState(
        title = "t",
        subtitle = null,
        posterUrl = null,
        playerState = playerState,
        positionMs = 0,
        durationMs = 0,
        isLive = false,
        isSeekable = false,
        volume = 1.0,
        isMuted = false,
        playbackRate = 1.0,
        supportsPlaybackRate = false,
        textTracks = emptyList(),
        activeTextTrackId = null,
        queue = queue,
        currentItemId = currentItemId,
        idleReason = idleReason,
        originalUrl = null,
    )

    @Test
    fun `active playback is not ended`() {
        for (playerState in listOf(RemotePlayerState.PLAYING, RemotePlayerState.PAUSED, RemotePlayerState.BUFFERING, RemotePlayerState.LOADING)) {
            assertThat(ReceiverQueue.isEnded(state(playerState))).isFalse()
        }
    }

    @Test
    fun `stopped playback and a finished or failed last item end the queue`() {
        assertThat(ReceiverQueue.isEnded(state(RemotePlayerState.IDLE, "CANCELED", currentItemId = 1))).isTrue()
        assertThat(ReceiverQueue.isEnded(state(RemotePlayerState.IDLE, "FINISHED"))).isTrue()
        assertThat(ReceiverQueue.isEnded(state(RemotePlayerState.IDLE, "ERROR"))).isTrue()
        assertThat(ReceiverQueue.isEnded(state(RemotePlayerState.IDLE, "FINISHED", queue = emptyList(), currentItemId = null)))
            .isTrue()
    }

    @Test
    fun `an item finishing before the next one starts does not end the queue`() {
        assertThat(ReceiverQueue.isEnded(state(RemotePlayerState.IDLE, "FINISHED", currentItemId = 1))).isFalse()
    }

    @Test
    fun `brief idle states during a load do not count`() {
        assertThat(ReceiverQueue.isEnded(state(RemotePlayerState.IDLE, idleReason = null))).isFalse()
        assertThat(ReceiverQueue.isEnded(state(RemotePlayerState.IDLE, "INTERRUPTED"))).isFalse()
    }
}
