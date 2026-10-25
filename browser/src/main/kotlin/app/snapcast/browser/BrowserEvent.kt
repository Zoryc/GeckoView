package app.snapcast.browser

/** One-off notifications for the UI (snackbars, activities to launch, menus). */
sealed interface BrowserEvent {
    data class Message(val text: String) : BrowserEvent

    data class DownloadStarted(val fileName: String) : BrowserEvent

    data class DownloadFinished(val fileName: String, val contentUri: String?) : BrowserEvent

    data class DownloadFailed(val fileName: String, val reason: String) : BrowserEvent

    /** A link the engine cannot handle (intent:, mailto:, tel:, market:, app links). The UI starts an activity. */
    data class OpenExternal(val uri: String) : BrowserEvent

    data class TabCrashed(val tabId: String) : BrowserEvent

    enum class ContextElementType { NONE, IMAGE, VIDEO, AUDIO }

    /** Long-press on page content. */
    data class ContextMenu(
        val tabId: String,
        val linkUrl: String?,
        val srcUrl: String?,
        val type: ContextElementType,
        val title: String?,
        val altText: String?,
    ) : BrowserEvent
}
