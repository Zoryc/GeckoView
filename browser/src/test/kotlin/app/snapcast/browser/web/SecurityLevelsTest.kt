package app.snapcast.browser.web

import app.snapcast.browser.SecurityLevel
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class SecurityLevelsTest {
    private val unknown = 0
    private val blocked = 1
    private val loaded = 2

    private fun level(
        url: String,
        isSecure: Boolean = true,
        isException: Boolean = false,
        active: Int = unknown,
        passive: Int = unknown,
    ) = SecurityLevels.forPage(url, isSecure, isException, active, passive)

    @Test
    fun `valid https is secure, also when mixed content was blocked`() {
        assertThat(level("https://example.com")).isEqualTo(SecurityLevel.SECURE)
        assertThat(level("https://example.com", active = blocked, passive = blocked)).isEqualTo(SecurityLevel.SECURE)
    }

    @Test
    fun `loaded mixed content or a certificate exception breaks the lock`() {
        assertThat(level("https://example.com", active = loaded)).isEqualTo(SecurityLevel.BROKEN)
        assertThat(level("https://example.com", passive = loaded)).isEqualTo(SecurityLevel.BROKEN)
        assertThat(level("https://example.com", isException = true)).isEqualTo(SecurityLevel.BROKEN)
    }

    @Test
    fun `https without a secure connection is broken`() {
        assertThat(level("https://example.com", isSecure = false)).isEqualTo(SecurityLevel.BROKEN)
    }

    @Test
    fun `plain http is insecure`() {
        assertThat(level("http://example.com", isSecure = false)).isEqualTo(SecurityLevel.INSECURE)
    }

    @Test
    fun `built-in and local pages show no indicator`() {
        listOf(
            "about:blank",
            "about:config",
            "moz-extension://uuid/dashboard.html",
            "resource://android/x",
            "data:text/html,x",
            "snapcast:home",
            "file:///sdcard/page.html",
        ).forEach { url ->
            assertWithMessage(url).that(level(url, isSecure = false)).isEqualTo(SecurityLevel.NONE)
            assertWithMessage(url).that(level(url, isSecure = true)).isEqualTo(SecurityLevel.NONE)
        }
    }

    @Test
    fun `provisional level before security info arrives`() {
        assertThat(SecurityLevels.provisional("http://example.com")).isEqualTo(SecurityLevel.INSECURE)
        assertThat(SecurityLevels.provisional("https://example.com")).isEqualTo(SecurityLevel.NONE)
        assertThat(SecurityLevels.provisional("about:blank")).isEqualTo(SecurityLevel.NONE)
    }
}
