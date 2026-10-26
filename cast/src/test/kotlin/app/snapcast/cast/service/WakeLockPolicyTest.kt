package app.snapcast.cast.service

import app.snapcast.cast.RemotePlayerState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WakeLockPolicyTest {

    @Test
    fun `locks are held while the receiver fetches media`() {
        for (state in listOf(RemotePlayerState.PLAYING, RemotePlayerState.BUFFERING, RemotePlayerState.LOADING)) {
            assertThat(WakeLockPolicy.decide(state)).isEqualTo(WakeLockPolicy.Decision.HOLD)
        }
    }

    @Test
    fun `locks are released a minute after playback pauses or stops`() {
        for (state in listOf(RemotePlayerState.PAUSED, RemotePlayerState.IDLE, null)) {
            assertThat(WakeLockPolicy.decide(state)).isEqualTo(WakeLockPolicy.Decision.RELEASE_AFTER_DELAY)
        }
        assertThat(WakeLockPolicy.RELEASE_DELAY_MS).isEqualTo(60_000L)
    }
}
