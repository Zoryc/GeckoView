package app.snapcast.ui.util

import java.net.URLDecoder

/**
 * Pure parsing of Android `intent:` URIs (`intent://host/path#Intent;scheme=https;package=x;S.browser_fallback_url=...;end`)
 * as produced by web pages, without android.content.Intent so it can be unit-tested.
 */
object IntentUriParser {
    private const val FRAGMENT_PREFIX = "#Intent;"

    /** True for `intent:` URIs (case-insensitive scheme). */
    fun isIntentUri(uri: String): Boolean = uri.trimStart().startsWith("intent:", ignoreCase = true)

    /**
     * The decoded `S.browser_fallback_url` extra of an intent URI when it is an http(s) URL, else null.
     * Pages use it as the URL to open when no app can handle the intent.
     */
    fun fallbackUrl(uri: String): String? {
        val value = attributes(uri)["S.browser_fallback_url"] ?: return null
        val decoded = decode(value) ?: return null
        val trimmed = decoded.trim()
        return trimmed.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    /** The `package=` attribute of an intent URI, or null. */
    fun packageName(uri: String): String? = attributes(uri)["package"]?.takeIf { it.isNotBlank() }

    /** Key/value attributes between `#Intent;` and `;end` (values still percent-encoded). */
    internal fun attributes(uri: String): Map<String, String> {
        if (!isIntentUri(uri)) return emptyMap()
        val start = uri.indexOf(FRAGMENT_PREFIX)
        if (start < 0) return emptyMap()
        val body = uri.substring(start + FRAGMENT_PREFIX.length)
        val result = LinkedHashMap<String, String>()
        for (part in body.split(';')) {
            if (part == "end") break
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            result[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return result
    }

    private fun decode(value: String): String? = try {
        // The String overload: the Charset one needs API 33 on Android.
        URLDecoder.decode(value, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }
}
