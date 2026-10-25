package app.snapcast.browser.web

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class ExternalSchemesTest {
    @Test
    fun `pages Gecko renders stay in the engine`() {
        listOf(
            "https://example.com/",
            "HTTP://EXAMPLE.COM",
            "about:blank",
            "data:text/html,hi",
            "blob:https://example.com/1234",
            "javascript:void(0)",
            "moz-extension://abc/popup.html",
            "resource://android/assets/x.js",
            "view-source:https://example.com",
            "file:///sdcard/a.html",
        ).forEach { assertWithMessage(it).that(ExternalSchemes.isExternal(it)).isFalse() }
    }

    @Test
    fun `app links go to Android`() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "mailto:someone@example.com",
            "tel:+15551234",
            "sms:+15551234?body=hi",
            "geo:37.78,-122.41",
            "market://details?id=org.mozilla.firefox",
            "whatsapp://send?text=hi",
            "myapp-callback://done",
        ).forEach { assertWithMessage(it).that(ExternalSchemes.isExternal(it)).isTrue() }
    }

    @Test
    fun `strings without a valid scheme are not external`() {
        assertThat(ExternalSchemes.isExternal("example.com")).isFalse()
        assertThat(ExternalSchemes.isExternal("1http://x")).isFalse()
        assertThat(ExternalSchemes.isExternal(":nothing")).isFalse()
        assertThat(ExternalSchemes.isExternal("")).isFalse()
    }

    @Test
    fun `app links need a user gesture or a direct navigation`() {
        val tel = "tel:+15551234"
        assertThat(ExternalSchemes.decide(tel, hasUserGesture = true, isDirectNavigation = false))
            .isEqualTo(ExternalSchemes.Decision.OPEN_EXTERNALLY)
        assertThat(ExternalSchemes.decide(tel, hasUserGesture = false, isDirectNavigation = true))
            .isEqualTo(ExternalSchemes.Decision.OPEN_EXTERNALLY)
        assertThat(ExternalSchemes.decide(tel, hasUserGesture = false, isDirectNavigation = false))
            .isEqualTo(ExternalSchemes.Decision.BLOCK)
        assertThat(ExternalSchemes.decide("https://example.com", hasUserGesture = false, isDirectNavigation = false))
            .isEqualTo(ExternalSchemes.Decision.ENGINE)
    }
}
