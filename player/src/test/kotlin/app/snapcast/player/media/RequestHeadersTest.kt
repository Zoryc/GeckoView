package app.snapcast.player.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RequestHeadersTest {

    @Test
    fun userAgentAndCookieAreSplitFromTheOtherHeaders() {
        val headers = RequestHeaders.from(
            mapOf(
                "Referer" to "https://site.example/watch",
                "User-Agent" to "Mozilla/5.0 (Android 15; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0",
                "Cookie" to "session=abc; consent=1",
                "Origin" to "https://site.example",
            ),
        )

        assertThat(headers.userAgent).isEqualTo("Mozilla/5.0 (Android 15; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0")
        assertThat(headers.cookie).isEqualTo("session=abc; consent=1")
        assertThat(headers.properties).containsExactly(
            "Referer", "https://site.example/watch",
            "Origin", "https://site.example",
        ).inOrder()
    }

    @Test
    fun cookieNameIsMatchedCaseInsensitivelyAndNeverReplayedToEveryHost() {
        val headers = RequestHeaders.from(
            linkedMapOf("cookie" to "old=1", "COOKIE" to "session=abc", "Authorization" to "Bearer token"),
        )

        assertThat(headers.cookie).isEqualTo("session=abc")
        assertThat(headers.properties.keys.map { it.lowercase() }).doesNotContain("cookie")
        assertThat(headers.properties).containsExactly("Authorization", "Bearer token")
    }

    @Test
    fun userAgentNameIsMatchedCaseInsensitively() {
        val headers = RequestHeaders.from(mapOf("user-agent" to "UA/1", "X-Requested-With" to "XMLHttpRequest"))

        assertThat(headers.userAgent).isEqualTo("UA/1")
        assertThat(headers.properties).containsExactly("X-Requested-With", "XMLHttpRequest")
    }

    @Test
    fun missingUserAgentIsNull() {
        val headers = RequestHeaders.from(mapOf("Referer" to "https://site.example/"))

        assertThat(headers.userAgent).isNull()
        assertThat(headers.properties).containsExactly("Referer", "https://site.example/")
    }

    @Test
    fun emptyInputGivesNothing() {
        assertThat(RequestHeaders.from(emptyMap()))
            .isEqualTo(RequestHeaders(userAgent = null, properties = emptyMap(), cookie = null))
    }

    @Test
    fun blankNamesAndValuesAreDroppedAndOthersTrimmed() {
        val headers = RequestHeaders.from(
            mapOf(" Referer " to "  https://site.example/  ", "Cookie" to "   ", "" to "x", "User-Agent" to " "),
        )

        assertThat(headers.userAgent).isNull()
        assertThat(headers.cookie).isNull()
        assertThat(headers.properties).containsExactly("Referer", "https://site.example/")
    }

    @Test
    fun headersManagedByTheDataSourceAreDropped() {
        val headers = RequestHeaders.from(
            mapOf(
                "Range" to "bytes=0-",
                "Host" to "cdn.example",
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "content-length" to "0",
                "If-None-Match" to "\"etag\"",
                ":authority" to "cdn.example",
                "Authorization" to "Bearer token",
            ),
        )

        assertThat(headers.properties).containsExactly("Authorization", "Bearer token")
    }

    @Test
    fun headersOkHttpWouldRejectAreDropped() {
        val headers = RequestHeaders.from(
            mapOf(
                "Cookie" to "name=café",
                "X-Bad Name" to "value",
                "X-Control" to "a\u0001b",
                "X-Tab" to "a\tb",
                "User-Agent" to "UA ☃",
            ),
        )

        assertThat(headers.userAgent).isNull()
        assertThat(headers.cookie).isNull()
        assertThat(headers.properties).containsExactly("X-Tab", "a\tb")
    }

    @Test
    fun laterDuplicateReplacesEarlierRegardlessOfCase() {
        val headers = RequestHeaders.from(
            linkedMapOf("referer" to "https://old.example/", "Accept" to "*/*", "Referer" to "https://new.example/"),
        )

        assertThat(headers.properties).containsExactly(
            "Referer", "https://new.example/",
            "Accept", "*/*",
        ).inOrder()
    }

    @Test
    fun cookiesByHostAreNormalizedAndFiltered() {
        val cookies = RequestHeaders.cookiesByHost(
            linkedMapOf(
                "CDN.Site.com." to " cdn=1 ",
                "[::1]" to "local=1",
                "blank.example" to "   ",
                "bad.example" to "name=café",
                "ctl.example" to "a\u0001b",
                " " to "nohost=1",
                "cdn.site.com" to "cdn=2",
            ),
        )

        assertThat(cookies).containsExactly(
            "cdn.site.com", "cdn=2",
            "::1", "local=1",
        ).inOrder()
    }

    @Test
    fun hostsAreNormalizedLikeTheProxy() {
        assertThat(RequestHeaders.normalizeHost(" WWW.Example.COM. ")).isEqualTo("www.example.com")
        assertThat(RequestHeaders.normalizeHost("[2001:DB8::1]")).isEqualTo("2001:db8::1")
        assertThat(RequestHeaders.normalizeHost("")).isEmpty()
    }
}
