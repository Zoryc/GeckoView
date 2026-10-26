package app.snapcast.player.media

import java.util.Locale

/**
 * Captured browser headers prepared for Media3's OkHttp data source: the User-Agent goes to
 * `setUserAgent`, the Cookie and Authorization to [ScopedCookieInterceptor] (they may only reach the host
 * they were captured for), everything else to `setDefaultRequestProperties`.
 *
 * Headers the data source or OkHttp manage per request (Range, Host, Accept-Encoding, hop-by-hop and
 * conditional headers) are dropped so they are never sent twice, and so are names/values OkHttp would
 * reject (it throws on non-ASCII or control characters, which would fail every request).
 */
internal data class RequestHeaders(
    val userAgent: String?,
    val properties: Map<String, String>,
    /** Cookie captured on the request for the played URL; never part of [properties]. */
    val cookie: String? = null,
    /** Authorization captured on the request for the played URL; never part of [properties]. */
    val authorization: String? = null,
) {
    companion object {
        /** Splits [headers]; names are matched case-insensitively and a later duplicate replaces an earlier one. */
        fun from(headers: Map<String, String>): RequestHeaders {
            var userAgent: String? = null
            var cookie: String? = null
            var authorization: String? = null
            val byLowerName = LinkedHashMap<String, Pair<String, String>>()
            for ((rawName, rawValue) in headers) {
                val name = rawName.trim()
                val value = rawValue.trim()
                if (!isValidName(name) || name.startsWith(':') || value.isEmpty() || !isValidValue(value)) continue
                val lower = name.lowercase(Locale.ROOT)
                when (lower) {
                    USER_AGENT -> userAgent = value
                    COOKIE -> cookie = value
                    AUTHORIZATION -> authorization = value
                    in MANAGED_HEADERS -> Unit
                    else -> byLowerName[lower] = name to value
                }
            }
            val properties = LinkedHashMap<String, String>(byLowerName.size)
            byLowerName.values.forEach { (name, value) -> properties[name] = value }
            return RequestHeaders(userAgent, properties, cookie, authorization)
        }

        /**
         * Per-host Cookie headers keyed by [normalizeHost]d host; entries with a blank host or a blank value,
         * or a value OkHttp would reject, are dropped. A later duplicate host replaces an earlier one.
         */
        fun cookiesByHost(cookies: Map<String, String>): Map<String, String> {
            val result = LinkedHashMap<String, String>(cookies.size)
            for ((rawHost, rawValue) in cookies) {
                val host = normalizeHost(rawHost)
                val value = rawValue.trim()
                if (host.isEmpty() || value.isEmpty() || !isValidValue(value)) continue
                result[host] = value
            }
            return result
        }

        /** Host as compared for cookie scoping: lower case, without IPv6 brackets or a trailing dot. */
        fun normalizeHost(host: String): String =
            host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").trimEnd('.')

        /** Same rule as OkHttp's header name check: visible ASCII only. */
        private fun isValidName(name: String): Boolean =
            name.isNotEmpty() && name.all { it in '!'..'~' }

        /** Same rule as OkHttp's header value check: tab or printable ASCII. */
        private fun isValidValue(value: String): Boolean =
            value.all { it == '\t' || it in ' '..'~' }

        private const val USER_AGENT = "user-agent"
        private const val COOKIE = "cookie"
        private const val AUTHORIZATION = "authorization"

        private val MANAGED_HEADERS = setOf(
            "host",
            "content-length",
            "range",
            "if-range",
            "if-none-match",
            "if-modified-since",
            "accept-encoding",
            "connection",
            "keep-alive",
            "proxy-connection",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade",
        )
    }
}
