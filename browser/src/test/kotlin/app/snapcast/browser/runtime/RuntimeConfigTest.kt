package app.snapcast.browser.runtime

import app.snapcast.core.model.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuntimeConfigTest {
    // GeckoRuntimeSettings.COLOR_SCHEME_* values from the GeckoView API.
    private val light = 0
    private val dark = 1
    private val system = -1

    @Test
    fun `web content color scheme follows the app theme`() {
        assertThat(RuntimeConfig.colorSchemeFor(ThemeMode.DARK)).isEqualTo(dark)
        assertThat(RuntimeConfig.colorSchemeFor(ThemeMode.LIGHT)).isEqualTo(light)
        assertThat(RuntimeConfig.colorSchemeFor(ThemeMode.SYSTEM)).isEqualTo(system)
    }

    @Test
    fun `tracking protection uses the standard lists and partitions third-party cookies`() {
        // AntiTracking.DEFAULT | AntiTracking.STP and CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS.
        assertThat(RuntimeConfig.ANTI_TRACKING_CATEGORIES).isEqualTo(46 or 256)
        assertThat(RuntimeConfig.COOKIE_BEHAVIOR).isEqualTo(5)
    }
}
