package app.snapcast.cast.media

/** "Resume where you left off" decisions for cast playback. */
internal object ResumePolicy {
    /** True when a saved position should be looked up before loading. */
    fun shouldLookUp(requestedMs: Long?, resumeEnabled: Boolean, isLive: Boolean): Boolean =
        requestedMs == null && resumeEnabled && !isLive

    /**
     * Start position for a load: an explicit [requestedMs] always wins; otherwise the [savedMs] position
     * when resuming is enabled and the stream is not live; otherwise the beginning.
     */
    fun startPositionMs(requestedMs: Long?, resumeEnabled: Boolean, isLive: Boolean, savedMs: Long?): Long = when {
        requestedMs != null -> requestedMs.coerceAtLeast(0)
        !resumeEnabled || isLive -> 0
        else -> savedMs?.coerceAtLeast(0) ?: 0
    }

    /** Positions are kept only for finite, non-private media while resuming is enabled. */
    fun shouldSave(resumeEnabled: Boolean, isLive: Boolean, isPrivate: Boolean, positionMs: Long, durationMs: Long): Boolean =
        resumeEnabled && !isLive && !isPrivate && durationMs > 0 && positionMs in 0..durationMs
}
