package app.snapcast

import android.content.Intent
import app.snapcast.ui.ExternalRequest

/** Maps launcher / share / search intents to [ExternalRequest]s. Pure so it can be unit-tested. */
internal object ExternalRequestParser {
    private val urlInText = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun parse(
        action: String?,
        dataString: String?,
        sharedText: String?,
        searchQuery: String?,
        openCastController: Boolean,
        toUrl: (String) -> String,
    ): ExternalRequest? {
        if (openCastController) return ExternalRequest.OpenCastController
        return when (action) {
            Intent.ACTION_VIEW -> dataString
                ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                ?.let { ExternalRequest.OpenUrl(it) }
            Intent.ACTION_SEND -> sharedText?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                val url = urlInText.find(text)?.value?.trimEnd('.', ',', ')', ']')
                ExternalRequest.OpenUrl(url ?: toUrl(text))
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> searchQuery?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { ExternalRequest.OpenUrl(toUrl(it)) }
            else -> null
        }
    }
}
