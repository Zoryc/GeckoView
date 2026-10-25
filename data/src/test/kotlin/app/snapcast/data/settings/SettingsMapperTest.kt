package app.snapcast.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.snapcast.core.model.AppSettings
import app.snapcast.core.model.SearchEngine
import app.snapcast.core.model.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsMapperTest {

    private val custom = AppSettings(
        searchEngine = SearchEngine.BRAVE,
        homePage = "https://example.com/",
        desktopModeDefault = true,
        themeMode = ThemeMode.SYSTEM,
        minVideoSizeBytes = 2_000_000L,
        minVideoDurationSec = 0,
        ignoredDomains = setOf("ads.example.com", "tracker.test"),
        proxyEnabled = false,
        proxyPort = 9000,
        videoButtonTipShown = true,
        resumePlayback = false,
    )

    @Test
    fun emptyPreferencesReadAsDefaults() {
        assertThat(SettingsMapper.read(emptyPreferences())).isEqualTo(AppSettings())
    }

    @Test
    fun writeThenReadRoundTrips() {
        val prefs = mutablePreferencesOf()
        SettingsMapper.write(prefs, custom)
        assertThat(SettingsMapper.read(prefs)).isEqualTo(custom)
    }

    @Test
    fun enumsAreStoredByName() {
        val prefs = mutablePreferencesOf()
        SettingsMapper.write(prefs, custom)
        assertThat(prefs[SettingsMapper.SEARCH_ENGINE]).isEqualTo("BRAVE")
        assertThat(prefs[SettingsMapper.THEME_MODE]).isEqualTo("SYSTEM")
        assertThat(prefs[SettingsMapper.IGNORED_DOMAINS]).containsExactly("ads.example.com", "tracker.test")
    }

    @Test
    fun defaultValuesAreNotStored() {
        val prefs = mutablePreferencesOf()
        SettingsMapper.write(prefs, AppSettings())
        assertThat(prefs.asMap()).isEmpty()
    }

    @Test
    fun resettingToDefaultRemovesTheKey() {
        val prefs = mutablePreferencesOf()
        SettingsMapper.write(prefs, custom)
        SettingsMapper.write(prefs, custom.copy(proxyPort = AppSettings.DEFAULT_PROXY_PORT))
        assertThat(prefs.contains(SettingsMapper.PROXY_PORT)).isFalse()
        assertThat(SettingsMapper.read(prefs).proxyPort).isEqualTo(AppSettings.DEFAULT_PROXY_PORT)
    }

    @Test
    fun emptyIgnoreListIsKeptInsteadOfFallingBackToDefaults() {
        val prefs = mutablePreferencesOf()
        SettingsMapper.write(prefs, AppSettings(ignoredDomains = emptySet()))
        assertThat(SettingsMapper.read(prefs).ignoredDomains).isEmpty()
    }

    @Test
    fun unknownEnumNamesFallBackToDefaults() {
        val prefs = mutablePreferencesOf(
            SettingsMapper.SEARCH_ENGINE to "ALTAVISTA",
            SettingsMapper.THEME_MODE to "",
        )
        val read = SettingsMapper.read(prefs)
        assertThat(read.searchEngine).isEqualTo(AppSettings().searchEngine)
        assertThat(read.themeMode).isEqualTo(AppSettings().themeMode)
    }

    @Test
    fun outOfRangeNumbersFallBackToDefaults() {
        val defaults = AppSettings()
        listOf(0, 80, 1023, 65536, -1).forEach { port ->
            val read = SettingsMapper.read(mutablePreferencesOf(SettingsMapper.PROXY_PORT to port))
            assertThat(read.proxyPort).isEqualTo(defaults.proxyPort)
        }
        val read = SettingsMapper.read(
            mutablePreferencesOf(
                SettingsMapper.MIN_VIDEO_SIZE_BYTES to -5L,
                SettingsMapper.MIN_VIDEO_DURATION_SEC to -1,
                SettingsMapper.HOME_PAGE to "   ",
            ),
        )
        assertThat(read.minVideoSizeBytes).isEqualTo(defaults.minVideoSizeBytes)
        assertThat(read.minVideoDurationSec).isEqualTo(defaults.minVideoDurationSec)
        assertThat(read.homePage).isEqualTo(AppSettings.HOME_PAGE_BUILT_IN)
    }

    @Test
    fun portRangeBoundsAreAccepted() {
        listOf(1024, 65535).forEach { port ->
            val read = SettingsMapper.read(mutablePreferencesOf(SettingsMapper.PROXY_PORT to port))
            assertThat(read.proxyPort).isEqualTo(port)
        }
    }

    @Test
    fun valueOfUnexpectedTypeReadsAsDefault() {
        val prefs = mutablePreferencesOf(
            intPreferencesKey("min_video_size_bytes") to 5,
            stringPreferencesKey("proxy_port") to "9000",
            stringPreferencesKey("proxy_enabled") to "false",
        )
        val read = SettingsMapper.read(prefs)
        assertThat(read.minVideoSizeBytes).isEqualTo(AppSettings.DEFAULT_MIN_VIDEO_SIZE)
        assertThat(read.proxyPort).isEqualTo(AppSettings.DEFAULT_PROXY_PORT)
        assertThat(read.proxyEnabled).isTrue()
    }

    @Test
    fun writeLeavesForeignKeysAlone() {
        val flag = booleanPreferencesKey("data_default_bookmarks_seeded")
        val prefs = mutablePreferencesOf(flag to true)
        SettingsMapper.write(prefs, AppSettings())
        SettingsMapper.write(prefs, custom)
        assertThat(prefs[flag]).isTrue()
    }

    @Test
    fun writeSanitizesValues() {
        val prefs = mutablePreferencesOf()
        SettingsMapper.write(
            prefs,
            custom.copy(
                homePage = "  ",
                minVideoSizeBytes = -1L,
                minVideoDurationSec = -3,
                proxyPort = 80,
                ignoredDomains = setOf(" Ads.Example.COM ", "", "*.cdn.test"),
            ),
        )
        val read = SettingsMapper.read(prefs)
        assertThat(read.homePage).isEqualTo(AppSettings.HOME_PAGE_BUILT_IN)
        assertThat(read.minVideoSizeBytes).isEqualTo(0L)
        assertThat(read.minVideoDurationSec).isEqualTo(0)
        assertThat(read.proxyPort).isEqualTo(AppSettings.DEFAULT_PROXY_PORT)
        assertThat(read.ignoredDomains).containsExactly("ads.example.com", "cdn.test")
    }

    @Test
    fun sanitizeKeepsPreviousPortWhenNewOneIsInvalid() {
        val previous = AppSettings(proxyPort = 9100)
        val sanitized = SettingsMapper.sanitize(previous.copy(proxyPort = 70_000), fallback = previous)
        assertThat(sanitized.proxyPort).isEqualTo(9100)
        assertThat(SettingsMapper.sanitize(AppSettings(proxyPort = 2048)).proxyPort).isEqualTo(2048)
    }

    @Test
    fun sanitizeLeavesValidSettingsUnchanged() {
        assertThat(SettingsMapper.sanitize(custom)).isEqualTo(custom)
        assertThat(SettingsMapper.sanitize(AppSettings())).isEqualTo(AppSettings())
    }

    @Test
    fun normalizeDomainStripsSchemePathPortAndWildcards() {
        assertThat(SettingsMapper.normalizeDomain(" https://Ads.Example.com:443/path?q=1 ")).isEqualTo("ads.example.com")
        assertThat(SettingsMapper.normalizeDomain("*.doubleclick.net")).isEqualTo("doubleclick.net")
        assertThat(SettingsMapper.normalizeDomain(".example.org.")).isEqualTo("example.org")
        assertThat(SettingsMapper.normalizeDomain("user@host.test")).isEqualTo("host.test")
        assertThat(SettingsMapper.normalizeDomain("   ")).isNull()
        assertThat(SettingsMapper.normalizeDomain("two words")).isNull()
    }
}
