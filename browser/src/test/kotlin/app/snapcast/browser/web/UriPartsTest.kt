package app.snapcast.browser.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UriPartsTest {
    @Test
    fun `scheme is lower-cased and validated`() {
        assertThat(UriParts.schemeOf("HTTPS://Example.com")).isEqualTo("https")
        assertThat(UriParts.schemeOf("moz-extension://id/x")).isEqualTo("moz-extension")
        assertThat(UriParts.schemeOf("view-source:https://a")).isEqualTo("view-source")
        assertThat(UriParts.schemeOf("example.com/path")).isNull()
        assertThat(UriParts.schemeOf("9p://x")).isNull()
        assertThat(UriParts.schemeOf("a b:c")).isNull()
    }

    @Test
    fun `origin keeps scheme host and port only`() {
        assertThat(UriParts.originOf("https://User:Pw@Example.COM:8443/a/b?q#f")).isEqualTo("https://example.com:8443")
        assertThat(UriParts.originOf("http://example.com")).isEqualTo("http://example.com")
        assertThat(UriParts.originOf("http://example.com?x=1")).isEqualTo("http://example.com")
        assertThat(UriParts.originOf("about:blank")).isEqualTo("about")
        assertThat(UriParts.originOf("no scheme")).isNull()
    }

    @Test
    fun `host without port or brackets`() {
        assertThat(UriParts.hostOf("https://www.example.com:443/x")).isEqualTo("www.example.com")
        assertThat(UriParts.hostOf("http://[::1]:8080/")).isEqualTo("::1")
        assertThat(UriParts.hostOf("about:blank")).isNull()
        assertThat(UriParts.hostOf("file:///sdcard/a")).isNull()
    }
}
