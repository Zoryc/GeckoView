package app.snapcast.ui.browser

/**
 * Drops a snackbar message identical to the previous one shown within [windowMs], e.g. when both an
 * action result and a controller event report "Added to queue" for the same video.
 */
class DuplicateMessageFilter(private val windowMs: Long = 2_000L) {
    private var lastText: String? = null
    private var lastAt: Long = Long.MIN_VALUE

    /** Returns true when [text] should be shown now (and remembers it). */
    fun shouldShow(text: String, nowMs: Long): Boolean {
        val duplicate = text == lastText && lastAt != Long.MIN_VALUE && nowMs - lastAt in 0..windowMs
        if (!duplicate) {
            lastText = text
            lastAt = nowMs
        }
        return !duplicate
    }
}
