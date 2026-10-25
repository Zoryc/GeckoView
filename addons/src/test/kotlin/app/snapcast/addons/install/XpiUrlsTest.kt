package app.snapcast.addons.install

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XpiUrlsTest {

    @Test
    fun `amo latest and file downloads are install urls`() {
        assertThat(XpiUrls.isInstallUrl("https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi")).isTrue()
        assertThat(XpiUrls.isInstallUrl("https://addons.mozilla.org/firefox/downloads/file/4458450/ublock_origin-1.63.2.xpi")).isTrue()
    }

    @Test
    fun `amo download endpoints without xpi suffix are install urls`() {
        assertThat(XpiUrls.isInstallUrl("https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/")).isTrue()
        assertThat(XpiUrls.isInstallUrl("https://addons.mozilla.org/en-US/firefox/downloads/file/123/addon?src=dp-btn")).isTrue()
        assertThat(XpiUrls.isInstallUrl("https://ADDONS.mozilla.org/android/downloads/latest/darkreader/platform:7")).isTrue()
    }

    @Test
    fun `xpi suffix matches on any https host ignoring query fragment and case`() {
        assertThat(XpiUrls.isInstallUrl("https://cdn.example.com/files/My-Addon.XPI")).isTrue()
        assertThat(XpiUrls.isInstallUrl("https://example.com/addon.xpi?download=1#top")).isTrue()
        assertThat(XpiUrls.isInstallUrl("http://user:pw@example.com:8080/a/b.xpi")).isTrue()
        assertThat(XpiUrls.isInstallUrl("  https://example.com/addon.xpi  ")).isTrue()
    }

    @Test
    fun `regular pages are not install urls`() {
        assertThat(XpiUrls.isInstallUrl("https://addons.mozilla.org/en-US/firefox/addon/ublock-origin/")).isFalse()
        assertThat(XpiUrls.isInstallUrl("https://addons.mozilla.org/firefox/downloads/")).isFalse()
        assertThat(XpiUrls.isInstallUrl("https://example.com/firefox/downloads/latest/addon/")).isFalse()
        assertThat(XpiUrls.isInstallUrl("https://example.com/page?file=addon.xpi")).isFalse()
        assertThat(XpiUrls.isInstallUrl("https://example.com/page#addon.xpi")).isFalse()
        assertThat(XpiUrls.isInstallUrl("https://example.com/addon.xpi.html")).isFalse()
    }

    @Test
    fun `non http schemes are not install urls`() {
        assertThat(XpiUrls.isInstallUrl("file:///sdcard/Download/addon.xpi")).isFalse()
        assertThat(XpiUrls.isInstallUrl("javascript:alert('a.xpi')")).isFalse()
        assertThat(XpiUrls.isInstallUrl("moz-extension://abc/page.xpi")).isFalse()
        assertThat(XpiUrls.isInstallUrl("addon.xpi")).isFalse()
        assertThat(XpiUrls.isInstallUrl("")).isFalse()
    }

    @Test
    fun `xpi mime type is recognised with parameters and any case`() {
        assertThat(XpiUrls.isXpiMimeType("application/x-xpinstall")).isTrue()
        assertThat(XpiUrls.isXpiMimeType("Application/X-XPInstall; charset=binary")).isTrue()
        assertThat(XpiUrls.isXpiMimeType("application/zip")).isFalse()
        assertThat(XpiUrls.isXpiMimeType(null)).isFalse()
    }

    @Test
    fun `responses match by content type or by url`() {
        val xpiHeaders = mapOf("content-type" to "application/x-xpinstall")
        assertThat(XpiUrls.isXpiResponse("https://example.com/download?id=7", xpiHeaders)).isTrue()
        assertThat(XpiUrls.isXpiResponse("https://example.com/addon.xpi", mapOf("Content-Type" to "application/octet-stream"))).isTrue()
        assertThat(XpiUrls.isXpiResponse("https://example.com/video.mp4", mapOf("Content-Type" to "video/mp4"))).isFalse()
        assertThat(XpiUrls.isXpiResponse("https://example.com/file", emptyMap())).isFalse()
    }
}
