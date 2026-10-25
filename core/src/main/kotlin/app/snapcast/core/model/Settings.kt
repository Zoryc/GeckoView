package app.snapcast.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SearchEngine(val displayName: String, val queryTemplate: String, val homeUrl: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/"),
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com/"),
    BING("Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com/"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s", "https://search.brave.com/"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s", "https://www.startpage.com/"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s", "https://www.ecosia.org/");
}

@Serializable
enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** All user settings. Stored by the data module (DataStore); defaults live here. */
@Serializable
data class AppSettings(
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    /** [HOME_PAGE_BUILT_IN] shows the speed-dial home screen; otherwise an absolute URL. */
    val homePage: String = HOME_PAGE_BUILT_IN,
    val desktopModeDefault: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** Progressive files (MP4/WebM/…) smaller than this are ignored when their size is known. */
    val minVideoSizeBytes: Long = DEFAULT_MIN_VIDEO_SIZE,
    /** Videos shorter than this (seconds) are ignored when their duration is known. 0 disables. */
    val minVideoDurationSec: Int = DEFAULT_MIN_DURATION_SEC,
    /** Hosts (and their subdomains) whose media is never listed, e.g. ad servers. */
    val ignoredDomains: Set<String> = DEFAULT_IGNORED_DOMAINS,
    val proxyEnabled: Boolean = true,
    val proxyPort: Int = DEFAULT_PROXY_PORT,
    val videoButtonTipShown: Boolean = false,
    /** Resume cast/local playback where it was left off. */
    val resumePlayback: Boolean = true,
) {
    companion object {
        const val HOME_PAGE_BUILT_IN = "snapcast:home"
        const val DEFAULT_MIN_VIDEO_SIZE = 512L * 1024
        const val DEFAULT_MIN_DURATION_SEC = 5
        const val DEFAULT_PROXY_PORT = 8642
        val DEFAULT_IGNORED_DOMAINS = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adnxs.com",
            "imasdk.googleapis.com",
            "moatads.com",
            "spotxchange.com",
            "springserve.com",
        )
    }
}
