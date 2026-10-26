package app.snapcast.player.resume

/**
 * Where playback of a new item starts.
 *
 * @property positionMs Position to seek to, or null for the item's default position (the start of
 *   on-demand media, the live edge of a live stream).
 * @property resumed True when [positionMs] comes from "resume where you left off", so the UI offers
 *   "Start over".
 */
internal data class StartPosition(val positionMs: Long?, val resumed: Boolean) {

    companion object {
        val DEFAULT = StartPosition(positionMs = null, resumed = false)

        /** True when the saved position must be looked up before deciding (no explicit start, resume on). */
        fun needsSavedPosition(explicitMs: Long?, resumeEnabled: Boolean): Boolean =
            explicitMs == null && resumeEnabled

        /**
         * An explicit start ([explicitMs]: requested by the caller or restored after recreation) always
         * wins; otherwise a positive [savedMs] is resumed when [resumeEnabled].
         */
        fun decide(explicitMs: Long?, resumeEnabled: Boolean, savedMs: Long?): StartPosition = when {
            explicitMs != null -> if (explicitMs > 0) StartPosition(explicitMs, resumed = false) else DEFAULT
            resumeEnabled && savedMs != null && savedMs > 0 -> StartPosition(savedMs, resumed = true)
            else -> DEFAULT
        }

        /**
         * True when the current position is worth handing to the position store: on-demand media whose
         * duration is known. (Media3 reports an unknown duration as a negative value.)
         */
        fun isPersistable(durationMs: Long, isLive: Boolean): Boolean = !isLive && durationMs > 0

        /**
         * True when positions may be written at all: "Resume playback" is on and the media does not come
         * from a private tab (the rule of the cast module's ResumePolicy.shouldSave).
         */
        fun mayStore(resumeEnabled: Boolean, isPrivate: Boolean): Boolean = resumeEnabled && !isPrivate
    }
}
