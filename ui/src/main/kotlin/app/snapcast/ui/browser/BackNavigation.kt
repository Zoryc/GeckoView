package app.snapcast.ui.browser

/** What a system back press does on the browser screen. */
enum class BackAction {
    /** Close the open sheet / menu / address-bar editing first. */
    DISMISS_OVERLAY,
    EXIT_FULLSCREEN,
    GO_BACK,

    /** The first page of a tab that was loaded from the built-in home page returns to it. */
    GO_HOME,

    /** A tab opened by another tab (popup, target=_blank, extension) closes and returns to its parent. */
    CLOSE_TAB,

    /** Not handled: the system finishes the activity. */
    SYSTEM,
}

/** Inputs of [BackNavigation.decide]; a snapshot of the selected tab and the screen. */
data class BackState(
    val overlayOpen: Boolean,
    val hasTab: Boolean,
    val isFullscreen: Boolean,
    val canGoBack: Boolean,
    val isHome: Boolean,
    val openedFromHome: Boolean,
    val hasParent: Boolean,
)

/** Back-press policy of the browser screen, kept free of Android types so it is unit-testable. */
object BackNavigation {
    fun decide(state: BackState): BackAction = when {
        state.overlayOpen -> BackAction.DISMISS_OVERLAY
        !state.hasTab -> BackAction.SYSTEM
        state.isFullscreen -> BackAction.EXIT_FULLSCREEN
        state.canGoBack -> BackAction.GO_BACK
        !state.isHome && state.openedFromHome -> BackAction.GO_HOME
        state.hasParent -> BackAction.CLOSE_TAB
        else -> BackAction.SYSTEM
    }
}
