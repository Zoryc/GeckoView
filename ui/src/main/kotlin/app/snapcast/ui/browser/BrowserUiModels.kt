package app.snapcast.ui.browser

import app.snapcast.browser.BrowserEvent
import app.snapcast.cast.CastEvent
import app.snapcast.cast.CastResult
import app.snapcast.core.model.DetectedVideo

/** Long-press menu on page content; [video] is set when the element is a castable video. */
data class ContextMenuState(
    val event: BrowserEvent.ContextMenu,
    val video: DetectedVideo?,
)

/** One-off events for the browser screen, buffered by the view model until the screen collects them. */
sealed interface BrowserUiEvent {
    data class Browser(val event: BrowserEvent) : BrowserUiEvent

    data class Cast(val event: CastEvent) : BrowserUiEvent

    /** Result of a cast / add-to-queue request started from the UI. */
    data class CastOutcome(val result: CastResult, val video: DetectedVideo, val queued: Boolean) : BrowserUiEvent

    data class BookmarkToggled(val bookmarked: Boolean) : BrowserUiEvent

    /** A link opened in a background tab; the snackbar offers to switch to it. */
    data class OpenedInBackground(val tabId: String, val isPrivate: Boolean) : BrowserUiEvent
}
