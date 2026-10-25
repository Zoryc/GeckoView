package app.snapcast.browser

import app.snapcast.core.model.AppSettings

enum class SecurityLevel {
    /** Built-in pages (home, about:, moz-extension:). */
    NONE,

    /** HTTPS with a valid certificate and no mixed content. */
    SECURE,

    /** Plain HTTP. */
    INSECURE,

    /** HTTPS with mixed active/display content or a certificate exception. */
    BROKEN,
}

/** Immutable snapshot of one tab, published by [BrowserTabs.tabs]. */
data class TabState(
    val id: String,
    /** Current URL; [AppSettings.HOME_PAGE_BUILT_IN] while the built-in home page is shown. */
    val url: String,
    val title: String,
    val isPrivate: Boolean,
    val isLoading: Boolean = false,
    /** 0..100 */
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val security: SecurityLevel = SecurityLevel.NONE,
    val isDesktopMode: Boolean = false,
    val isFullscreen: Boolean = false,
    val isMediaPlaying: Boolean = false,
    /** Tab that opened this one (popup / target=_blank / extension), if any. */
    val parentId: String? = null,
    val lastAccessed: Long = 0L,
) {
    val isHome: Boolean get() = url == AppSettings.HOME_PAGE_BUILT_IN
}
