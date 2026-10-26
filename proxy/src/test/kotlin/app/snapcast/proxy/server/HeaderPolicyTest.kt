package app.snapcast.proxy.server

import com.google.common.truth.Truth.assertThat
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
    fun `a blank authorization is not sent`() {
        val blank = HeaderPolicy(mapOf("Authorization" to " "), "https://api.site.com/m.m3u8", emptyMap(), null)
        assertThat(blank.headersFor("https://api.site.com/seg.ts".toHttpUrl(), dropAuthorization = false)["Authorization"]).isNull()
    }
}
