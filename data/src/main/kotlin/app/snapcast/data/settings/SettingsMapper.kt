package app.snapcast.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.snapcast.core.model.AppSettings
import java.util.Locale

/**
 * Maps [AppSettings] to and from DataStore [Preferences], one key per field.
 *
 * * Enums are stored by name; unknown names fall back to the default.
 * * Values with the wrong type, out-of-range numbers (proxy port outside [PROXY_PORT_RANGE], negative sizes)
 *   and blank home pages read as the default.
 * * Values equal to their default are not stored (the key is removed), so improved defaults in later versions
 *   reach users who never changed them.
 * * Keys that do not belong to [AppSettings] are left untouched.
 */
internal object SettingsMapper {
    val SEARCH_ENGINE = stringPreferencesKey("search_engine")
    val HOME_PAGE = stringPreferencesKey("home_page")
    val DESKTOP_MODE_DEFAULT = booleanPreferencesKey("desktop_mode_default")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val MIN_VIDEO_SIZE_BYTES = longPreferencesKey("min_video_size_bytes")
    val MIN_VIDEO_DURATION_SEC = intPreferencesKey("min_video_duration_sec")
    val IGNORED_DOMAINS = stringSetPreferencesKey("ignored_domains")
    val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
    val PROXY_PORT = intPreferencesKey("proxy_port")
    val VIDEO_BUTTON_TIP_SHOWN = booleanPreferencesKey("video_button_tip_shown")
    val RESUME_PLAYBACK = booleanPreferencesKey("resume_playback")

    /** Unprivileged TCP ports the local proxy may listen on. */
    val PROXY_PORT_RANGE: IntRange = 1024..65535

    private val DEFAULTS = AppSettings()

    fun read(prefs: Preferences): AppSettings = AppSettings(
        searchEngine = enumOrDefault(prefs.value(SEARCH_ENGINE), DEFAULTS.searchEngine),
        homePage = prefs.value(HOME_PAGE)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULTS.homePage,
        desktopModeDefault = prefs.value(DESKTOP_MODE_DEFAULT) ?: DEFAULTS.desktopModeDefault,
        themeMode = enumOrDefault(prefs.value(THEME_MODE), DEFAULTS.themeMode),
        minVideoSizeBytes = prefs.value(MIN_VIDEO_SIZE_BYTES)?.takeIf { it >= 0L } ?: DEFAULTS.minVideoSizeBytes,
        minVideoDurationSec = prefs.value(MIN_VIDEO_DURATION_SEC)?.takeIf { it >= 0 } ?: DEFAULTS.minVideoDurationSec,
        ignoredDomains = prefs.stringSet(IGNORED_DOMAINS)?.let { normalizeDomains(it) } ?: DEFAULTS.ignoredDomains,
        proxyEnabled = prefs.value(PROXY_ENABLED) ?: DEFAULTS.proxyEnabled,
        proxyPort = prefs.value(PROXY_PORT)?.takeIf { it in PROXY_PORT_RANGE } ?: DEFAULTS.proxyPort,
        videoButtonTipShown = prefs.value(VIDEO_BUTTON_TIP_SHOWN) ?: DEFAULTS.videoButtonTipShown,
        resumePlayback = prefs.value(RESUME_PLAYBACK) ?: DEFAULTS.resumePlayback,
    )

    /** Writes the [sanitize]d form of [settings] into [prefs]. */
    fun write(prefs: MutablePreferences, settings: AppSettings) {
        val s = sanitize(settings)
        prefs.putOrRemove(SEARCH_ENGINE, s.searchEngine.name, DEFAULTS.searchEngine.name)
        prefs.putOrRemove(HOME_PAGE, s.homePage, DEFAULTS.homePage)
        prefs.putOrRemove(DESKTOP_MODE_DEFAULT, s.desktopModeDefault, DEFAULTS.desktopModeDefault)
        prefs.putOrRemove(THEME_MODE, s.themeMode.name, DEFAULTS.themeMode.name)
        prefs.putOrRemove(MIN_VIDEO_SIZE_BYTES, s.minVideoSizeBytes, DEFAULTS.minVideoSizeBytes)
        prefs.putOrRemove(MIN_VIDEO_DURATION_SEC, s.minVideoDurationSec, DEFAULTS.minVideoDurationSec)
        prefs.putOrRemove(IGNORED_DOMAINS, s.ignoredDomains, DEFAULTS.ignoredDomains)
        prefs.putOrRemove(PROXY_ENABLED, s.proxyEnabled, DEFAULTS.proxyEnabled)
        prefs.putOrRemove(PROXY_PORT, s.proxyPort, DEFAULTS.proxyPort)
        prefs.putOrRemove(VIDEO_BUTTON_TIP_SHOWN, s.videoButtonTipShown, DEFAULTS.videoButtonTipShown)
        prefs.putOrRemove(RESUME_PLAYBACK, s.resumePlayback, DEFAULTS.resumePlayback)
    }

    /**
     * Makes [settings] storable: a blank home page becomes the built-in one, negative sizes/durations become 0,
     * domains are normalized ([normalizeDomain]) and an invalid proxy port is replaced by [fallback]'s port
     * (or the default port when that is invalid too).
     */
    fun sanitize(settings: AppSettings, fallback: AppSettings = DEFAULTS): AppSettings {
        val port = when {
            settings.proxyPort in PROXY_PORT_RANGE -> settings.proxyPort
            fallback.proxyPort in PROXY_PORT_RANGE -> fallback.proxyPort
            else -> DEFAULTS.proxyPort
        }
        return settings.copy(
            homePage = settings.homePage.trim().ifEmpty { AppSettings.HOME_PAGE_BUILT_IN },
            minVideoSizeBytes = settings.minVideoSizeBytes.coerceAtLeast(0L),
            minVideoDurationSec = settings.minVideoDurationSec.coerceAtLeast(0),
            ignoredDomains = normalizeDomains(settings.ignoredDomains),
            proxyPort = port,
        )
    }

    fun normalizeDomains(domains: Iterable<String>): Set<String> =
        domains.mapNotNullTo(LinkedHashSet<String>()) { normalizeDomain(it) }

    /**
     * Reduces user input such as `" https://Ads.Example.com:443/path "` or `"*.example.com"` to a bare lower-case
     * host (`ads.example.com`, `example.com`); null when nothing usable remains.
     */
    fun normalizeDomain(raw: String): String? {
        var host = raw.trim().lowercase(Locale.ROOT)
        host = host.substringAfter("://")
        host = host.substringBefore('/').substringBefore('?').substringBefore('#')
        host = host.substringAfterLast('@')
        host = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
        host = host.removePrefix("*.").trim('.')
        return host.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        if (name == null) default else enumValues<E>().firstOrNull { it.name == name } ?: default

    /** Typed read that treats a value of an unexpected type as absent instead of throwing. */
    private inline fun <reified T : Any> Preferences.value(key: Preferences.Key<T>): T? = asMap()[key] as? T

    private fun Preferences.stringSet(key: Preferences.Key<Set<String>>): Set<String>? =
        (asMap()[key] as? Set<*>)?.filterIsInstance<String>()?.toSet()

    private fun <T : Any> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T, default: T) {
        if (value == default) remove(key) else set(key, value)
    }
}
