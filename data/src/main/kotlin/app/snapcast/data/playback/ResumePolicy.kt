package app.snapcast.data.playback

/** Decides whether a playback position is worth remembering for "resume where you left off". */
internal object ResumePolicy {
    /** Positions before this are treated as "not really started". */
    const val MIN_POSITION_MS = 10_000L

    /** Positions this close to the end are treated as "finished". */
    const val END_MARGIN_MS = 30_000L

    /** Positions at or beyond 95 % of the duration are treated as "finished" (remaining <= duration / 20). */
    private const val FINISHED_REMAINDER_DIVISOR = 20L

    /**
     * True when [positionMs] should be stored: at least [MIN_POSITION_MS] in, more than [END_MARGIN_MS] before
     * the end and below 95 % of [durationMs]. Unknown or non-positive durations (live streams, not-yet-prepared
     * media) are never stored, since there is nothing meaningful to resume.
     */
    fun shouldRemember(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        if (positionMs < MIN_POSITION_MS) return false
        val remainingMs = durationMs - positionMs
        if (remainingMs <= END_MARGIN_MS) return false
        // For integer positions, position >= 0.95 * duration  <=>  remaining <= floor(duration / 20).
        return remainingMs > durationMs / FINISHED_REMAINDER_DIVISOR
    }
}
