package app.snapcast.browser.web

import app.snapcast.browser.BrowserTabs
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClearDataFlagsTest {
    // StorageController.ClearFlags values from the GeckoView API.
    private val cookies = 1L
    private val networkCache = 2L
    private val imageCache = 4L
    private val domStorages = 16L
    private val authSessions = 32L
    private val permissions = 64L
    private val siteSettings = 192L
    private val siteData = 471L

    @Test
    fun `nothing selected clears nothing`() {
        assertThat(ClearDataFlags.toGecko(0)).isEqualTo(0L)
    }

    @Test
    fun `cookies include http auth sessions`() {
        assertThat(ClearDataFlags.toGecko(BrowserTabs.CLEAR_COOKIES)).isEqualTo(cookies or authSessions)
    }

    @Test
    fun `cache clears network and image caches only`() {
        assertThat(ClearDataFlags.toGecko(BrowserTabs.CLEAR_CACHE)).isEqualTo(networkCache or imageCache)
    }

    @Test
    fun `site data clears storage and site settings but keeps cookies`() {
        val flags = ClearDataFlags.toGecko(BrowserTabs.CLEAR_SITE_DATA)
        assertThat(flags).isEqualTo(domStorages or siteSettings)
        assertThat(flags and permissions).isEqualTo(permissions)
        assertThat(flags and cookies).isEqualTo(0L)
    }

    @Test
    fun `cookies plus site data clear everything a site stored`() {
        val flags = ClearDataFlags.toGecko(BrowserTabs.CLEAR_COOKIES or BrowserTabs.CLEAR_SITE_DATA or BrowserTabs.CLEAR_CACHE)
        assertThat(flags and siteData).isEqualTo(siteData)
        assertThat(flags and authSessions).isEqualTo(authSessions)
    }
}
