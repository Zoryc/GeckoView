package app.snapcast.browser.web

/**
 * Decides which top-level navigations leave the engine for another app (intent:, mailto:, tel:,
 * sms:, geo:, market:, whatsapp:, custom app schemes, ...). Everything Gecko can render itself
 * stays in the browser.
 */
internal object ExternalSchemes {
    /** Schemes Gecko loads itself. Any other scheme is handed to Android. */
    private val ENGINE_SCHEMES = setOf(
        "http", "https", "about", "data", "blob", "file", "javascript", "moz-extension",
        "resource", "view-source", "chrome", "ws", "wss", "jar", "moz-icon", "snapcast",
    )

    enum class Decision {
        /** Gecko handles the load. */
        ENGINE,

        /** Hand the URI to another app and cancel the load. */
        OPEN_EXTERNALLY,

        /** Cancel the load silently (an app link the user did not ask for, e.g. an ad redirect). */
        BLOCK,
    }

    fun isExternal(uri: String): Boolean {
        val scheme = UriParts.schemeOf(uri) ?: return false
        return scheme !in ENGINE_SCHEMES
    }

    /**
     * Like Chrome, app links only open on user activation (a tap, including redirects that started
     * from one) or when the app itself asked to load the URI; otherwise pages could launch other
     * apps on their own.
     */
    fun decide(uri: String, hasUserGesture: Boolean, isDirectNavigation: Boolean): Decision = when {
        !isExternal(uri) -> Decision.ENGINE
        hasUserGesture || isDirectNavigation -> Decision.OPEN_EXTERNALLY
        else -> Decision.BLOCK
    }
}
