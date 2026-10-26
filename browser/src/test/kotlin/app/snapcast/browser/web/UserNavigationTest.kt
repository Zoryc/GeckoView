package app.snapcast.browser.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserNavigationTest {
    private var now = 1_000L
    private val navigation = UserNavigation { now }

    private fun request(
        gesture: Boolean = false,
        direct: Boolean = false,
        redirect: Boolean = false,
        newWindow: Boolean = false,
    ): Boolean = navigation.onTopLevelLoadRequest(gesture, direct, redirect, newWindow)

    @Test
    fun `taps and app loads are the user's, page loads are not`() {
        assertThat(request(gesture = true)).isTrue()
        assertThat(request(direct = true)).isTrue()
        assertThat(request()).isFalse()
    }

    @Test
    fun `redirect hops of the user's navigation inherit it`() {
        request(gesture = true)
        now += 500
        assertThat(request(redirect = true)).isTrue()
        now += 500
        assertThat(request(redirect = true)).isTrue()
    }

    @Test
    fun `redirect hops of a page's own navigation are not the user's`() {
        request(gesture = true)
        request()
        assertThat(request(redirect = true)).isFalse()
    }

    @Test
    fun `a redirect without a pending user navigation is not the user's`() {
        assertThat(request(redirect = true)).isFalse()
    }

    @Test
    fun `nothing is inherited once the navigation ended`() {
        request(direct = true)
        navigation.onNavigationEnded()
        assertThat(request(redirect = true)).isFalse()
    }

    @Test
    fun `nothing is inherited after the redirect window`() {
        request(gesture = true)
        now += UserNavigation.REDIRECT_WINDOW_MS + 1
        assertThat(request(redirect = true)).isFalse()
    }

    @Test
    fun `the window counts from the user's request, not from the last hop`() {
        request(gesture = true)
        now += UserNavigation.REDIRECT_WINDOW_MS - 1
        assertThat(request(redirect = true)).isTrue()
        now += 2
        assertThat(request(redirect = true)).isFalse()
    }

    @Test
    fun `loads into a new window do not change this session's navigation`() {
        request(gesture = true)
        assertThat(request(newWindow = true)).isFalse()
        assertThat(request(redirect = true)).isTrue()

        navigation.onNavigationEnded()
        assertThat(request(gesture = true, newWindow = true)).isTrue()
        assertThat(request(redirect = true)).isFalse()
    }
}
