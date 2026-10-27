package app.snapcast.player.media

import com.google.common.truth.Truth.assertThat
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class SessionCookieJarTest {
    private fun SessionCookieJar.save(url: String, setCookie: String) {
        val httpUrl = url.toHttpUrl()
        saveFromResponse(httpUrl, listOfNotNull(Cookie.parse(httpUrl, setCookie)))
    }

    private fun SessionCookieJar.header(url: String) = loadForRequest(url.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }

    @Test
    fun cookiesComeInBrowserOrderLongerPathsFirst() {
        val jar = SessionCookieJar()
        // No Path attribute: each cookie gets the directory of the response that set it.
        jar.save("https://cdn.site.com/v/master.m3u8", "tok=OLD")
        jar.save("https://cdn.site.com/v/720/index.m3u8", "tok=NEW")
        assertThat(jar.header("https://cdn.site.com/v/720/seg1.ts")).isEqualTo("tok=NEW; tok=OLD")
        assertThat(jar.header("https://cdn.site.com/v/480/seg1.ts")).isEqualTo("tok=OLD")
    }

    @Test
    fun aReplacedCookieKeepsItsPlaceAndAnExpiredOneIsRemoved() {
        val jar = SessionCookieJar()
        jar.save("https://cdn.site.com/", "a=1; Path=/")
        jar.save("https://cdn.site.com/", "b=1; Path=/")
        jar.save("https://cdn.site.com/", "a=2; Path=/")
        assertThat(jar.header("https://cdn.site.com/x")).isEqualTo("a=2; b=1")
        jar.save("https://cdn.site.com/", "a=; Path=/; Max-Age=0")
        assertThat(jar.header("https://cdn.site.com/x")).isEqualTo("b=1")
    }
}
