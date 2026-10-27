package app.snapcast.ui.browser

import app.snapcast.browser.BrowserEvent
import app.snapcast.cast.CastEvent
import app.snapcast.cast.CastResult
import app.snapcast.core.model.DetectedVideo
import app.snapcast.player.PlaybackQuality

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

    /** The player's link was refused: the page is being reloaded to play the video again with a fresh link. */
    data object ReloadingForReplay : BrowserUiEvent

    /** The reloaded page showed the video again: play it at the quality picked before (if any), from [startPositionMs]. */
    data class PlayOnPhone(val video: DetectedVideo, val quality: PlaybackQuality?, val startPositionMs: Long?) : BrowserUiEvent

    /** The reloaded page did not show the same video again in time: the user picks it in the list. */
    data object ReplayNotFound : BrowserUiEvent
}
