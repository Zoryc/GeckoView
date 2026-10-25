package app.snapcast.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackNavigationTest {
    private val webPage = BackState(
        overlayOpen = false,
        hasTab = true,
        isFullscreen = false,
        canGoBack = false,
        isHome = false,
        openedFromHome = false,
        hasParent = false,
    )

    @Test
    fun `open overlay is dismissed before anything else`() {
        val state = webPage.copy(overlayOpen = true, isFullscreen = true, canGoBack = true)
        assertThat(BackNavigation.decide(state)).isEqualTo(BackAction.DISMISS_OVERLAY)
    }

    @Test
    fun `fullscreen is exited before going back`() {
        val state = webPage.copy(isFullscreen = true, canGoBack = true)
        assertThat(BackNavigation.decide(state)).isEqualTo(BackAction.EXIT_FULLSCREEN)
    }

    @Test
    fun `history goes back when possible`() {
        val state = webPage.copy(canGoBack = true, openedFromHome = true, hasParent = true)
        assertThat(BackNavigation.decide(state)).isEqualTo(BackAction.GO_BACK)
    }

    @Test
    fun `first page loaded from home returns home`() {
        val state = webPage.copy(openedFromHome = true, hasParent = true)
        assertThat(BackNavigation.decide(state)).isEqualTo(BackAction.GO_HOME)
    }

    @Test
    fun `home page itself does not go home again`() {
        val state = webPage.copy(isHome = true, openedFromHome = true)
        assertThat(BackNavigation.decide(state)).isEqualTo(BackAction.SYSTEM)
    }

    @Test
    fun `child tab closes back to its parent`() {
        val state = webPage.copy(hasParent = true)
        assertThat(BackNavigation.decide(state)).isEqualTo(BackAction.CLOSE_TAB)
    }

    @Test
    fun `child tab on its home page still closes`() {
        val state = webPage.copy(isHome = true, hasParent = true)
        assertThat(BackNavigation.decide(state)).isEqualTo(BackAction.CLOSE_TAB)
    }

    @Test
    fun `nothing to do leaves back to the system`() {
        assertThat(BackNavigation.decide(webPage)).isEqualTo(BackAction.SYSTEM)
    }

    @Test
    fun `no tab leaves back to the system`() {
        val state = webPage.copy(hasTab = false, canGoBack = true)
        assertThat(BackNavigation.decide(state)).isEqualTo(BackAction.SYSTEM)
    }
}
