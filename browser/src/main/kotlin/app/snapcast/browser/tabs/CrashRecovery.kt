package app.snapcast.browser.tabs

/** Pure rule that keeps a page which always crashes its content process from reloading in a loop. */
internal object CrashRecovery {
    /** A second crash or kill within this time of the previous one is treated as a crash loop. */
    const val RELOAD_WINDOW_MS = 30_000L

    /**
     * Whether the selected tab may be restored automatically after a crash or kill at [now], given the
     * time of its previous one ([lastCrashAt], null if none). Both are `SystemClock.elapsedRealtime()`.
     * When false the tab stays dormant until the user reloads or reselects it.
     */
    fun mayAutoRestore(lastCrashAt: Long?, now: Long): Boolean =
        lastCrashAt == null || now - lastCrashAt !in 0L until RELOAD_WINDOW_MS
}
