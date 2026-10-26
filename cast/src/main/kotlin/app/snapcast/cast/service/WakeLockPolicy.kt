package app.snapcast.cast.service

import app.snapcast.cast.RemotePlayerState

/**
 * When the casting service holds its partial wake lock (and its Wi-Fi lock): only while the receiver is
 * fetching from the proxy. While playback is paused or stopped the locks are released after
 * [RELEASE_DELAY_MS], so a paused or finished session does not keep the CPU and the Wi-Fi radio awake; they
 * are taken again as soon as playback resumes.
 */
internal object WakeLockPolicy {
    enum class Decision {
        /** Playing, buffering or loading: the receiver pulls media through the proxy. */
        HOLD,

        /** Paused, idle or nothing loaded: release unless playback resumes within [RELEASE_DELAY_MS]. */
        RELEASE_AFTER_DELAY,
    }

    /** Covers short pauses and the receiver buffering ahead after a pause. */
    const val RELEASE_DELAY_MS = 60_000L

    fun decide(state: RemotePlayerState?): Decision = when (state) {
        RemotePlayerState.PLAYING,
        RemotePlayerState.BUFFERING,
        RemotePlayerState.LOADING,
        -> Decision.HOLD
        RemotePlayerState.PAUSED,
        RemotePlayerState.IDLE,
        null,
        -> Decision.RELEASE_AFTER_DELAY
    }
}
