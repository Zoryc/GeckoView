package app.snapcast.core.browser

import app.snapcast.core.model.SearchEngine
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class UrlInputResolverTest {
    private val ddg = SearchEngine.DUCKDUCKGO

    private fun resolve(input: String, engine: SearchEngine = ddg) = UrlInputResolver.resolve(input, engine)

    @Test
    fun `full URLs with allowed schemes pass through`() {
        assertThat(resolve("https://example.com/a?b=c")).isEqualTo("https://example.com/a?b=c")
        assertThat(resolve("  http://example.com  ")).isEqualTo("http://example.com")
        assertThat(resolve("HTTPS://Example.com")).isEqualTo("HTTPS://Example.com")
        assertThat(resolve("about:addons")).isEqualTo("about:addons")
        assertThat(resolve("about:blank")).isEqualTo("about:blank")
        assertThat(resolve("moz-extension://1234-abcd/popup.html")).isEqualTo("moz-extension://1234-abcd/popup.html")
        assertThat(resolve("data:text/html,<h1>Hi there</h1>")).isEqualTo("data:text/html,<h1>Hi there</h1>")
        assertThat(resolve("file:///sdcard/Download/index.html")).isEqualTo("file:///sdcard/Download/index.html")
        assertThat(resolve("view-source:https://example.com")).isEqualTo("view-source:https://example.com")
        assertThat(resolve("resource://android/assets/x.html")).isEqualTo("resource://android/assets/x.html")
        assertThat(resolve("snapcast:home")).isEqualTo("snapcast:home")
    }

    @Test
    fun `host-like input gets https`() {
        assertThat(resolve("example.com")).isEqualTo("https://example.com")
        assertThat(resolve("www.example.com/path/to?q=1#top")).isEqualTo("https://www.example.com/path/to?q=1#top")
        assertThat(resolve("example.co.uk:8443/x")).isEqualTo("https://example.co.uk:8443/x")
        assertThat(resolve("sub-domain.example.io")).isEqualTo("https://sub-domain.example.io")
        assertThat(resolve("münchen.de")).isEqualTo("https://münchen.de")
        assertThat(resolve("xn--mnchen-3ya.de")).isEqualTo("https://xn--mnchen-3ya.de")
        assertThat(resolve("8.8.8.8")).isEqualTo("https://8.8.8.8")
        assertThat(resolve("[2001:db8::1]:8080/x")).isEqualTo("https://[2001:db8::1]:8080/x")
        assertThat(resolve("medium.com/@user/post")).isEqualTo("https://medium.com/@user/post")
    }

    @Test
    fun `localhost and private addresses get http`() {
        assertThat(resolve("localhost")).isEqualTo("http://localhost")
        assertThat(resolve("localhost:8080/admin")).isEqualTo("http://localhost:8080/admin")
        assertThat(resolve("dev.localhost:3000")).isEqualTo("http://dev.localhost:3000")
        assertThat(resolve("192.168.1.10")).isEqualTo("http://192.168.1.10")
        assertThat(resolve("10.0.0.2:8642/stream")).isEqualTo("http://10.0.0.2:8642/stream")
        assertThat(resolve("172.16.0.1")).isEqualTo("http://172.16.0.1")
        assertThat(resolve("172.31.255.255")).isEqualTo("http://172.31.255.255")
        assertThat(resolve("127.0.0.1:5000")).isEqualTo("http://127.0.0.1:5000")
        assertThat(resolve("[::1]")).isEqualTo("http://[::1]")
        assertThat(resolve("[::1]:8080")).isEqualTo("http://[::1]:8080")
        assertThat(resolve("172.32.0.1")).isEqualTo("https://172.32.0.1")
        assertThat(resolve("172.15.0.1")).isEqualTo("https://172.15.0.1")
        assertThat(resolve("192.169.0.1")).isEqualTo("https://192.169.0.1")
    }

    @Test
    fun `everything else becomes a search`() {
        assertThat(resolve("cats")).isEqualTo("https://duckduckgo.com/?q=cats")
        assertThat(resolve("funny cat videos")).isEqualTo("https://duckduckgo.com/?q=funny+cat+videos")
        assertThat(resolve("what is 2.5 kg")).isEqualTo("https://duckduckgo.com/?q=what+is+2.5+kg")
        assertThat(resolve("1.5")).isEqualTo("https://duckduckgo.com/?q=1.5")
        assertThat(resolve("999.1.1.1")).isEqualTo("https://duckduckgo.com/?q=999.1.1.1")
        assertThat(resolve("example.com/a b")).isEqualTo("https://duckduckgo.com/?q=example.com%2Fa+b")
        assertThat(resolve("me@example.com")).isEqualTo("https://duckduckgo.com/?q=me%40example.com")
        assertThat(resolve("c++ & rust?")).isEqualTo("https://duckduckgo.com/?q=c%2B%2B+%26+rust%3F")
        assertThat(resolve("mailto:a@b.c")).isEqualTo("https://duckduckgo.com/?q=mailto%3Aa%40b.c")
        assertThat(resolve("javascript:alert(1)")).isEqualTo("https://duckduckgo.com/?q=javascript%3Aalert%281%29")
        assertThat(resolve("example.com:99999")).isEqualTo("https://duckduckgo.com/?q=example.com%3A99999")
        assertThat(resolve("https://")).isEqualTo("https://duckduckgo.com/?q=https%3A%2F%2F")
    }

    @Test
    fun `search uses the configured engine`() {
        assertThat(resolve("kotlin flow", SearchEngine.GOOGLE)).isEqualTo("https://www.google.com/search?q=kotlin+flow")
        assertThat(resolve("kotlin", SearchEngine.BING)).isEqualTo("https://www.bing.com/search?q=kotlin")
        assertThat(UrlInputResolver.searchUrl("ü ö", SearchEngine.BRAVE)).isEqualTo("https://search.brave.com/search?q=%C3%BC+%C3%B6")
    }

    @Test
    fun `blank input opens the engine home page`() {
        assertThat(resolve("   ")).isEqualTo(ddg.homeUrl)
    }

    @Test
    fun `looksLikeUrl`() {
        assertThat(UrlInputResolver.looksLikeUrl("example.com")).isTrue()
        assertThat(UrlInputResolver.looksLikeUrl("https://x")).isTrue()
        assertThat(UrlInputResolver.looksLikeUrl("localhost:8080")).isTrue()
        assertThat(UrlInputResolver.looksLikeUrl("[::1]")).isTrue()
        assertThat(UrlInputResolver.looksLikeUrl("hello")).isFalse()
        assertThat(UrlInputResolver.looksLikeUrl("hello world.com")).isFalse()
        assertThat(UrlInputResolver.looksLikeUrl("")).isFalse()
    }

    @Test
    fun `displayText shows search terms for every engine`() {
        SearchEngine.entries.forEach { engine ->
            val url = UrlInputResolver.searchUrl("cute cats & dogs", engine)
            assertWithMessage(engine.name).that(UrlInputResolver.displayText(url)).isEqualTo("cute cats & dogs")
        }
        assertThat(UrlInputResolver.displayText("https://www.google.com/search?q=kotlin+flow&sca_esv=123"))
            .isEqualTo("kotlin flow")
    }

    @Test
    fun `displayText strips https and a lone trailing slash`() {
        assertThat(UrlInputResolver.displayText("https://example.com/")).isEqualTo("example.com")
        assertThat(UrlInputResolver.displayText("https://www.example.com/")).isEqualTo("www.example.com")
        assertThat(UrlInputResolver.displayText("https://example.com/a/b/")).isEqualTo("example.com/a/b/")
        assertThat(UrlInputResolver.displayText("https://example.com/watch?v=1")).isEqualTo("example.com/watch?v=1")
        assertThat(UrlInputResolver.displayText("http://example.com/")).isEqualTo("http://example.com")
        assertThat(UrlInputResolver.displayText("http://example.com/x")).isEqualTo("http://example.com/x")
        assertThat(UrlInputResolver.displayText("about:blank")).isEqualTo("about:blank")
        assertThat(UrlInputResolver.displayText("https://duckduckgo.com/")).isEqualTo("duckduckgo.com")
    }
}
