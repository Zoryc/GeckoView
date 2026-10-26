package app.snapcast.player.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Same cookie scoping as the cast proxy's HeaderPolicy.cookieFor. */
class ScopedCookieInterceptorTest {

    private val interceptor = ScopedCookieInterceptor(
        originUrl = "https://Site.COM/watch/master.m3u8?token=1",
        originCookie = "session=abc",
        cookiesByHost = mapOf(
            "CDN.site.com" to "cdn=1",
            "site.com" to "fallback=1",
            "[::1]" to "local=1",
        ),
    )

    @Test
    fun originCookieGoesOnlyToTheHostOfThePlayedUrl() {
        assertThat(interceptor.cookieFor("site.com")).isEqualTo("session=abc")
        assertThat(interceptor.cookieFor("SITE.COM.")).isEqualTo("session=abc")
    }

    @Test
    fun otherHostsGetOnlyTheirOwnCookie() {
        assertThat(interceptor.cookieFor("cdn.site.com")).isEqualTo("cdn=1")
        assertThat(interceptor.cookieFor("::1")).isEqualTo("local=1")
    }

    @Test
    fun thirdPartyHostsGetNoCookie() {
        assertThat(interceptor.cookieFor("evil.example")).isNull()
        assertThat(interceptor.cookieFor("subs.site.com")).isNull()
        assertThat(interceptor.cookieFor("site.com.evil.example")).isNull()
    }

    @Test
    fun originHostFallsBackToItsPerHostCookieWhenNoneWasCaptured() {
        val withoutCookie = ScopedCookieInterceptor(
            originUrl = "https://site.com/v.mp4",
            originCookie = null,
            cookiesByHost = mapOf("site.com" to "fallback=1"),
        )

        assertThat(withoutCookie.cookieFor("site.com")).isEqualTo("fallback=1")
        assertThat(withoutCookie.cookieFor("cdn.site.com")).isNull()
    }

    @Test
    fun nonHttpOriginNeverGetsTheCapturedCookie() {
        val local = ScopedCookieInterceptor(
            originUrl = "content://media/external/video/7",
            originCookie = "session=abc",
            cookiesByHost = emptyMap(),
        )

        assertThat(local.cookieFor("media")).isNull()
        assertThat(local.cookieFor("")).isNull()
    }

    @Test
    fun invalidPerHostCookiesAreNeverSent() {
        val filtered = ScopedCookieInterceptor(
            originUrl = "https://site.com/v.mp4",
            originCookie = null,
            cookiesByHost = mapOf("cdn.site.com" to "name=café", "img.site.com" to " "),
        )

        assertThat(filtered.cookieFor("cdn.site.com")).isNull()
        assertThat(filtered.cookieFor("img.site.com")).isNull()
    }
}
