package app.snapcast.browser.web

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

class ErrorPagesTest {
    // WebRequestError categories and codes (values from the GeckoView API).
    private val categoryUnknown = 1
    private val categorySecurity = 2
    private val categoryNetwork = 3
    private val categorySafeBrowsing = 7

    @Test
    fun `specific codes map to readable kinds`() {
        assertThat(ErrorPages.kindOf(categoryNetwork, 37)).isEqualTo(LoadErrorKind.UNKNOWN_HOST)
        assertThat(ErrorPages.kindOf(categoryNetwork, 67)).isEqualTo(LoadErrorKind.CONNECTION_REFUSED)
        assertThat(ErrorPages.kindOf(categoryNetwork, 51)).isEqualTo(LoadErrorKind.TIMEOUT)
        assertThat(ErrorPages.kindOf(categoryNetwork, 115)).isEqualTo(LoadErrorKind.OFFLINE)
        assertThat(ErrorPages.kindOf(categorySecurity, 50)).isEqualTo(LoadErrorKind.BAD_CERTIFICATE)
        assertThat(ErrorPages.kindOf(categorySecurity, 34)).isEqualTo(LoadErrorKind.SECURITY)
        assertThat(ErrorPages.kindOf(categorySafeBrowsing, 87)).isEqualTo(LoadErrorKind.SAFE_BROWSING)
        assertThat(ErrorPages.kindOf(5, 53)).isEqualTo(LoadErrorKind.MALFORMED_URI)
        assertThat(ErrorPages.kindOf(4, 68)).isEqualTo(LoadErrorKind.CONTENT_CRASHED)
        assertThat(ErrorPages.kindOf(categoryNetwork, 99)).isEqualTo(LoadErrorKind.REDIRECT_LOOP)
    }

    @Test
    fun `unknown codes fall back to the category`() {
        assertThat(ErrorPages.kindOf(categoryNetwork, 17)).isEqualTo(LoadErrorKind.CONNECTION_INTERRUPTED)
        assertThat(ErrorPages.kindOf(categorySecurity, 17)).isEqualTo(LoadErrorKind.SECURITY)
        assertThat(ErrorPages.kindOf(categorySafeBrowsing, 9999)).isEqualTo(LoadErrorKind.SAFE_BROWSING)
        assertThat(ErrorPages.kindOf(categoryUnknown, 17)).isEqualTo(LoadErrorKind.UNKNOWN)
    }

    @Test
    fun `blocked or malformed addresses are not offered a retry`() {
        assertThat(ErrorPages.isRetryable(LoadErrorKind.TIMEOUT)).isTrue()
        assertThat(ErrorPages.isRetryable(LoadErrorKind.SAFE_BROWSING)).isFalse()
        assertThat(ErrorPages.isRetryable(LoadErrorKind.MALFORMED_URI)).isFalse()
    }

    @Test
    fun `html escapes all text and shows the failing url`() {
        val html = ErrorPages.html(
            title = "Can't <load>",
            message = "A & B",
            failedUrl = "https://example.com/?q=\"x\"&y=<z>",
            retryLabel = "Try again",
            codeLabel = "Error 37",
        )
        assertThat(html).contains("<title>Can&#39;t &lt;load&gt;</title>")
        assertThat(html).contains("A &amp; B")
        assertThat(html).contains("https://example.com/?q=&quot;x&quot;&amp;y=&lt;z&gt;")
        assertThat(html).contains("class=\"retry\" href=\"https://example.com/?q=&quot;x&quot;&amp;y=&lt;z&gt;\"")
        assertThat(html).doesNotContain("<z>")
    }

    @Test
    fun `retry link only for http urls and only with a label`() {
        val script = ErrorPages.html("t", "m", "javascript:alert(1)", "Try again", "c")
        assertThat(script).doesNotContain("class=\"retry\"")
        assertThat(script).contains("javascript:alert(1)")
        val noLabel = ErrorPages.html("t", "m", "https://example.com", retryLabel = null, codeLabel = "c")
        assertThat(noLabel).doesNotContain("class=\"retry\"")
        val noUrl = ErrorPages.html("t", "m", null, "Try again", "c")
        assertThat(noUrl).doesNotContain("class=\"url\"")
    }

    @Test
    fun `data uri is base64 utf-8 html`() {
        val html = "<p>€ #1 100%</p>"
        val uri = ErrorPages.dataUri(html)
        val prefix = "data:text/html;charset=utf-8;base64,"
        assertThat(uri).startsWith(prefix)
        val decoded = String(Base64.getDecoder().decode(uri.removePrefix(prefix)), Charsets.UTF_8)
        assertThat(decoded).isEqualTo(html)
    }
}
