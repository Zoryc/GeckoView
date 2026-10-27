package app.snapcast.proxy.server

import com.google.common.truth.Truth.assertThat
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class HeaderPolicyTest {
    private val policy = HeaderPolicy(
        headers = mapOf(
            "authorization" to "Bearer secret",
            "Referer" to "https://page.example/",
            "Cookie" to "session=1",
        ),
        originalUrl = "https://api.site.com/master.m3u8",
        cookiesByHost = emptyMap(),
        defaultUserAgent = "UA",
    )

    @Test
    fun `authorization goes to the original host only`() {
        val origin = policy.headersFor("https://api.site.com/seg.ts".toHttpUrl(), dropAuthorization = false)
        assertThat(origin["Authorization"]).isEqualTo("Bearer secret")
        assertThat(origin.values("Authorization")).hasSize(1)

        val thirdParty = policy.headersFor("https://cdn-b.example/seg.ts".toHttpUrl(), dropAuthorization = false)
        assertThat(thirdParty["Authorization"]).isNull()
        // The non-credential headers still go everywhere.
        assertThat(thirdParty["Referer"]).isEqualTo("https://page.example/")
        assertThat(thirdParty["User-Agent"]).isEqualTo("UA")
        assertThat(thirdParty["Cookie"]).isNull()
    }

    @Test
    fun `host comparison ignores case and a trailing dot`() {
        val headers = policy.headersFor("https://API.Site.com./seg.ts".toHttpUrl(), dropAuthorization = false)
        assertThat(headers["Authorization"]).isEqualTo("Bearer secret")
    }

    @Test
    fun `authorization is dropped after a cross-host redirect even on the original host`() {
        val headers = policy.headersFor("https://api.site.com/seg.ts".toHttpUrl(), dropAuthorization = true)
        assertThat(headers["Authorization"]).isNull()
        assertThat(headers["Cookie"]).isEqualTo("session=1")
    }

    @Test
    fun `cookies set by the upstream are sent back where they apply, newest value per name`() {
        val withTab = HeaderPolicy(
            headers = mapOf("Cookie" to "session=1; hdntl=old"),
            originalUrl = "https://api.site.com/master.m3u8",
            cookiesByHost = mapOf("api.site.com" to "session=2", "seg.cdn.example" to "edge=1"),
            defaultUserAgent = null,
        )
        val master = "https://api.site.com/master.m3u8".toHttpUrl()
        assertThat(withTab.headersFor(master, dropAuthorization = false)["Cookie"]).isEqualTo("session=2; hdntl=old")

        withTab.rememberCookies(master, headersOf("Set-Cookie", "hdntl=new; Path=/", "Set-Cookie", "scoped=1; Path=/hls/"))
        withTab.rememberCookies("https://seg.cdn.example/a.ts".toHttpUrl(), headersOf("Set-Cookie", "edge=2; Domain=cdn.example"))
        assertThat(withTab.headersFor("https://api.site.com/hls/v.m3u8".toHttpUrl(), dropAuthorization = false)["Cookie"])
            .isEqualTo("session=2; hdntl=new; scoped=1")
        assertThat(withTab.headersFor("https://api.site.com/other".toHttpUrl(), dropAuthorization = false)["Cookie"])
            .isEqualTo("session=2; hdntl=new")
        // A domain cookie reaches its subdomains, the captured one only its own host.
        assertThat(withTab.headersFor("https://seg.cdn.example/b.ts".toHttpUrl(), dropAuthorization = false)["Cookie"]).isEqualTo("edge=2")
        assertThat(withTab.headersFor("https://img.cdn.example/p.jpg".toHttpUrl(), dropAuthorization = false)["Cookie"]).isEqualTo("edge=2")
        assertThat(withTab.headersFor("https://other.example/x".toHttpUrl(), dropAuthorization = false)["Cookie"]).isNull()

        // An expired Set-Cookie removes the value.
        withTab.rememberCookies(master, headersOf("Set-Cookie", "scoped=; Path=/hls/; Max-Age=0"))
        assertThat(withTab.headersFor("https://api.site.com/hls/v.m3u8".toHttpUrl(), dropAuthorization = false)["Cookie"])
            .isEqualTo("session=2; hdntl=new")
    }

    @Test
    fun `a token renewed on a deeper path is sent first, as a browser orders cookies`() {
        val policy = HeaderPolicy(emptyMap(), "https://cdn.site.com/v/master.m3u8", emptyMap(), null)
        // No Path attribute: each cookie gets the directory of the response that set it.
        policy.rememberCookies("https://cdn.site.com/v/master.m3u8".toHttpUrl(), headersOf("Set-Cookie", "tok=OLD"))
        policy.rememberCookies("https://cdn.site.com/v/720/index.m3u8".toHttpUrl(), headersOf("Set-Cookie", "tok=NEW"))
        assertThat(policy.headersFor("https://cdn.site.com/v/720/seg1.ts".toHttpUrl(), dropAuthorization = false)["Cookie"]).isEqualTo("tok=NEW")
        assertThat(policy.headersFor("https://cdn.site.com/v/480/seg1.ts".toHttpUrl(), dropAuthorization = false)["Cookie"]).isEqualTo("tok=OLD")
    }

    @Test
    fun `a token set again outlives newer one-off cookies past the limit`() {
        val policy = HeaderPolicy(emptyMap(), "https://cdn.site.com/live/index.m3u8", emptyMap(), null)
        for (step in 0 until 120) {
            if (step % 5 == 0) policy.rememberCookies("https://cdn.site.com/live/index.m3u8".toHttpUrl(), headersOf("Set-Cookie", "hdntl=T$step; Path=/"))
            policy.rememberCookies("https://ads$step.site.com/x".toHttpUrl(), headersOf("Set-Cookie", "__cf_bm=$step; Path=/"))
            assertThat(policy.headersFor("https://cdn.site.com/live/seg$step.ts".toHttpUrl(), dropAuthorization = false)["Cookie"]).startsWith("hdntl=")
        }
    }

    @Test
    fun `a blank authorization is not sent`() {
        val blank = HeaderPolicy(mapOf("Authorization" to " "), "https://api.site.com/m.m3u8", emptyMap(), null)
        assertThat(blank.headersFor("https://api.site.com/seg.ts".toHttpUrl(), dropAuthorization = false)["Authorization"]).isNull()
    }
}
