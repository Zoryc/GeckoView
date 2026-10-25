package app.snapcast.cast.service

import app.snapcast.cast.CastConnectionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServiceStopPolicyTest {

    @Test
    fun `keeps running while media is loaded on a connected receiver`() {
        assertThat(ServiceStopPolicy.decide(CastConnectionState.CONNECTED, mediaLoaded = true))
            .isEqualTo(ServiceStopPolicy.Decision.KEEP)
    }

    @Test
    fun `waits when nothing is loaded or while reconnecting`() {
        assertThat(ServiceStopPolicy.decide(CastConnectionState.CONNECTED, mediaLoaded = false))
            .isEqualTo(ServiceStopPolicy.Decision.STOP_AFTER_GRACE)
        assertThat(ServiceStopPolicy.decide(CastConnectionState.CONNECTING, mediaLoaded = true))
            .isEqualTo(ServiceStopPolicy.Decision.STOP_AFTER_GRACE)
    }

    @Test
    fun `stops when the session is gone`() {
        for (state in listOf(CastConnectionState.NOT_CONNECTED, CastConnectionState.NO_DEVICES, CastConnectionState.UNAVAILABLE)) {
            assertThat(ServiceStopPolicy.decide(state, mediaLoaded = true)).isEqualTo(ServiceStopPolicy.Decision.STOP_NOW)
        }
    }
}
