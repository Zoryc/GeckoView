package app.snapcast.cast.service

import app.snapcast.cast.CastConnectionState

/** When the casting service may stop. */
internal object ServiceStopPolicy {
    enum class Decision {
        /** Something is loaded on a connected receiver. */
        KEEP,

        /** Nothing loaded, or reconnecting: stop unless this changes within the grace period. */
        STOP_AFTER_GRACE,

        /** The session is gone. */
        STOP_NOW,
    }

    const val GRACE_MS = 30_000L

    fun decide(state: CastConnectionState, mediaLoaded: Boolean): Decision = when (state) {
        CastConnectionState.CONNECTED -> if (mediaLoaded) Decision.KEEP else Decision.STOP_AFTER_GRACE
        CastConnectionState.CONNECTING -> Decision.STOP_AFTER_GRACE
        CastConnectionState.NOT_CONNECTED,
        CastConnectionState.NO_DEVICES,
        CastConnectionState.UNAVAILABLE,
        -> Decision.STOP_NOW
    }
}
