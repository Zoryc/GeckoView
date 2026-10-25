package app.snapcast.browser.web

import java.util.Locale

/** Minimal, allocation-light URI helpers on plain strings (no android.net.Uri, so they are JVM-testable). */
internal object UriParts {
    /** Lower-cased RFC 3986 scheme ("https", "intent", ...) or null when [uri] has none. */
    fun schemeOf(uri: String): String? {
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        val first = uri[0]
        if (!first.isAsciiLetter()) return null
        for (i in 1 until colon) {
            val c = uri[i]
            if (!(c.isAsciiLetter() || c in '0'..'9' || c == '+' || c == '-' || c == '.')) return null
        }
        return uri.substring(0, colon).lowercase(Locale.ROOT)
    }

    /**
     * "scheme://host[:port]" (lower-cased, user info removed) for hierarchical URIs, the bare scheme
     * for opaque ones ("about", "data"), or null when there is no scheme.
     */
    fun originOf(uri: String): String? {
        val scheme = schemeOf(uri) ?: return null
        val rest = uri.substring(scheme.length + 1)
        if (!rest.startsWith("//")) return scheme
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#'), startIndex = 2).let { if (it < 0) rest.length else it }
        val authority = rest.substring(2, authorityEnd).substringAfterLast('@').lowercase(Locale.ROOT)
        return "$scheme://$authority"
    }

    /** Host part of a hierarchical URI without port or brackets, or null. */
    fun hostOf(uri: String): String? {
        val origin = originOf(uri) ?: return null
        val authority = origin.substringAfter("://", missingDelimiterValue = "")
        if (authority.isEmpty()) return null
        if (authority.startsWith("[")) return authority.substringAfter('[').substringBefore(']').ifEmpty { null }
        return authority.substringBefore(':').ifEmpty { null }
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
