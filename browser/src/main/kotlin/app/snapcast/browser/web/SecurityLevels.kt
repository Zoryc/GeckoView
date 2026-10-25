package app.snapcast.browser.web

import app.snapcast.browser.SecurityLevel
import org.mozilla.geckoview.GeckoSession.ProgressDelegate.SecurityInformation

/** Maps GeckoView security information to the lock indicator shown in the address bar. */
internal object SecurityLevels {
    /** Built-in or local documents that never show a lock or a warning. */
    private val NEUTRAL_SCHEMES = setOf(
        "about", "moz-extension", "resource", "data", "chrome", "blob", "javascript", "file", "view-source", "snapcast", "jar",
    )

    /**
     * @param url the top-level document (its origin is enough)
     * @param isSecure the connection is encrypted with a trusted certificate
     * @param isException the user added a certificate exception for this site
     * @param mixedModeActive / [mixedModePassive] one of `SecurityInformation.CONTENT_*`
     */
    fun forPage(
        url: String,
        isSecure: Boolean,
        isException: Boolean,
        mixedModeActive: Int,
        mixedModePassive: Int,
    ): SecurityLevel {
        val scheme = UriParts.schemeOf(url) ?: return SecurityLevel.NONE
        if (scheme in NEUTRAL_SCHEMES) return SecurityLevel.NONE
        val mixedContent = mixedModeActive == SecurityInformation.CONTENT_LOADED ||
            mixedModePassive == SecurityInformation.CONTENT_LOADED
        return when {
            isSecure && (isException || mixedContent) -> SecurityLevel.BROKEN
            isSecure -> SecurityLevel.SECURE
            scheme == "https" || scheme == "wss" -> SecurityLevel.BROKEN
            else -> SecurityLevel.INSECURE
        }
    }

    /**
     * Best guess for a freshly committed location until GeckoView reports its security state:
     * plain HTTP is known to be insecure, everything else shows no indicator yet.
     */
    fun provisional(url: String): SecurityLevel = when (UriParts.schemeOf(url)) {
        "http", "ws" -> SecurityLevel.INSECURE
        else -> SecurityLevel.NONE
    }
}
