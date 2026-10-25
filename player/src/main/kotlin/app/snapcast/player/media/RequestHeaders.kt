package app.snapcast.player.media

import java.util.Locale

/**
 * Captured browser headers prepared for Media3's OkHttp data source: the User-Agent goes to
 * `setUserAgent`, everything else to `setDefaultRequestProperties`.
 *
 * Headers the data source or OkHttp manage per request (Range, Host, Accept-Encoding, hop-by-hop and
 * conditional headers) are dropped so they are never sent twice, and so are names/values OkHttp would
 * reject (it throws on non-ASCII or control characters, which would fail every request).
 */
internal data class RequestHeaders(
    val userAgent: String?,
    val properties: Map<String, String>,
) {
    companion object {
        /** Splits [headers]; names are matched case-insensitively and a later duplicate replaces an earlier one. */
        fun from(headers: Map<String, String>): RequestHeaders {
            var userAgent: String? = null
            val byLowerName = LinkedHashMap<String, Pair<String, String>>()
            for ((rawName, rawValue) in headers) {
                val name = rawName.trim()
                val value = rawValue.trim()
                if (!isValidName(name) || name.startsWith(':') || value.isEmpty() || !isValidValue(value)) continue
                val lower = name.lowercase(Locale.ROOT)
                when (lower) {
                    USER_AGENT -> userAgent = value
                    in MANAGED_HEADERS -> Unit
                    else -> byLowerName[lower] = name to value
                }
            }
            val properties = LinkedHashMap<String, String>(byLowerName.size)
            byLowerName.values.forEach { (name, value) -> properties[name] = value }
            return RequestHeaders(userAgent, properties)
        }

        /** Same rule as OkHttp's header name check: visible ASCII only. */
        private fun isValidName(name: String): Boolean =
            name.isNotEmpty() && name.all { it in '!'..'~' }

        /** Same rule as OkHttp's header value check: tab or printable ASCII. */
        private fun isValidValue(value: String): Boolean =
            value.all { it == '\t' || it in ' '..'~' }

        private const val USER_AGENT = "user-agent"

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
