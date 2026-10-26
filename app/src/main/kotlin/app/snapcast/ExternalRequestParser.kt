package app.snapcast

import android.content.Intent
import app.snapcast.core.util.UrlUtils
import app.snapcast.ui.ExternalRequest

/** Maps launcher / share / search intents to [ExternalRequest]s. Pure so it can be unit-tested. */
internal object ExternalRequestParser {
    private val urlInText = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /**
     * Every branch only ever yields an http(s) URL: the text comes from another app, so it is untrusted.
     * file:, data:, about:, view-source:, resource:, moz-extension: etc. would be loaded as an app-initiated
     * (system principal) navigation and could expose the app's private files.
     *
     * @param toUrl resolves address-bar style input (may return non-web schemes; those are rejected here).
     * @param searchUrl builds a search URL for [text]; must return http(s).
     */
    fun parse(
        action: String?,
        dataString: String?,
        sharedText: String?,
        searchQuery: String?,
        openCastController: Boolean,
        toUrl: (String) -> String,
        searchUrl: (String) -> String,
    ): ExternalRequest? {
        if (openCastController) return ExternalRequest.OpenCastController
        return when (action) {
            Intent.ACTION_VIEW -> dataString
                ?.takeIf(UrlUtils::isHttp)
                ?.let { ExternalRequest.OpenUrl(it) }
            Intent.ACTION_SEND -> sharedText?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                val url = urlInText.find(text)?.value?.trimEnd('.', ',', ')', ']')
                ExternalRequest.OpenUrl(url ?: safeUrl(text, toUrl, searchUrl))
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> searchQuery?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { ExternalRequest.OpenUrl(safeUrl(it, toUrl, searchUrl)) }
            else -> null
        }?.takeIf { it !is ExternalRequest.OpenUrl || UrlUtils.isHttp(it.url) }
    }

    /** [toUrl] of [text] when that is a web page, otherwise a search for [text]. */
    private fun safeUrl(text: String, toUrl: (String) -> String, searchUrl: (String) -> String): String =
        toUrl(text).takeIf(UrlUtils::isHttp) ?: searchUrl(text)
}
