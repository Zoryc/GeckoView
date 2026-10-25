package app.snapcast.core.browser

import app.snapcast.core.model.SearchEngine
import java.net.URLDecoder
import java.net.URLEncoder

/** Turns what the user typed in the address bar into a URL to load. */
object UrlInputResolver {
    /** Schemes typed URLs may use as-is. "snapcast" is the app's own home page ([app.snapcast.core.model.AppSettings.HOME_PAGE_BUILT_IN]). */
    private val PASS_THROUGH_SCHEMES = setOf(
        "http", "https", "about", "moz-extension", "data", "file", "view-source", "resource", "snapcast",
    )

    private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

    /**
     * Host, optional port, then an optional path/query/fragment, without whitespace. User info is not accepted
     * so that an e-mail address is searched rather than opened.
     */
    private val HOST_LIKE = Regex("^(\\[[0-9A-Fa-f:.]+\\]|[^\\s/?#:@\\[\\]]+)(?::(\\d{1,5}))?(?:[/?#]\\S*)?$")
    private val DOMAIN_LABEL = Regex("[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\-]*[\\p{L}\\p{N}])?")
    private val TOP_LEVEL_DOMAIN = Regex("\\p{L}{2,63}|xn--[a-z0-9\\-]+", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")

    /**
     * - Full URLs with a scheme (http, https, about, moz-extension, data, file, view-source, resource) pass through.
     * - Host-like input ("example.com", "example.com/path", "localhost:8080", "192.168.1.10", "[::1]") gets "https://"
     *   (or "http://" for localhost / private IPv4 addresses).
     * - Anything else (contains spaces, no dot, etc.) becomes a search with [engine], query URL-encoded.
     *
     * Blank input resolves to the engine's home page.
     */
    fun resolve(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return engine.homeUrl
        if (hasPassThroughScheme(text)) return text
        val host = hostOf(text) ?: return searchUrl(text, engine)
        val scheme = if (isLocalHost(host)) "http" else "https"
        return "$scheme://$text"
    }

    fun searchUrl(query: String, engine: SearchEngine): String =
        engine.queryTemplate.replace("%s", URLEncoder.encode(query.trim(), Charsets.UTF_8.name()))

    fun looksLikeUrl(input: String): Boolean {
        val text = input.trim()
        return text.isNotEmpty() && (hasPassThroughScheme(text) || hostOf(text) != null)
    }

    /** Text to show in the address bar for [url] (strips "https://", keeps search terms readable). */
    fun displayText(url: String): String {
        searchQueryOf(url)?.let { return it }
        var text = url
        if (text.startsWith("https://", ignoreCase = true)) text = text.substring("https://".length)
        // Drop the slash when it is the whole path ("example.com/"), never one ending a longer path.
        val hostStart = text.indexOf("://").let { if (it < 0) 0 else it + 3 }
        if (text.endsWith('/') && text.indexOf('/', hostStart) == text.length - 1) text = text.dropLast(1)
        return text
    }

    /** Decoded search terms when [url] was produced by one of the [SearchEngine] templates. */
    private fun searchQueryOf(url: String): String? {
        for (engine in SearchEngine.entries) {
            val prefix = engine.queryTemplate.substringBefore("%s")
            if (!url.startsWith(prefix, ignoreCase = true)) continue
            val encoded = url.substring(prefix.length).substringBefore('&').substringBefore('#')
            val query = try {
                URLDecoder.decode(encoded, Charsets.UTF_8.name())
            } catch (e: IllegalArgumentException) {
                encoded
            }
            return query.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun hasPassThroughScheme(text: String): Boolean {
        val scheme = SCHEME_PREFIX.find(text)?.groupValues?.get(1)?.lowercase() ?: return false
        if (scheme !in PASS_THROUGH_SCHEMES) return false
        // "http:" alone or "https:foo" are not usable URLs; hierarchical schemes need their "//".
        return when (scheme) {
            "http", "https", "moz-extension", "resource" -> text.length > scheme.length + 3 &&
                text.regionMatches(scheme.length, "://", 0, 3)
            else -> text.length > scheme.length + 1
        }
    }

    /** The host of host-like input (lower-cased), or null when the input is not host-like. */
    private fun hostOf(text: String): String? {
        val match = HOST_LIKE.matchEntire(text) ?: return null
        val host = match.groupValues[1].lowercase()
        val port = match.groupValues[2]
        if (port.isNotEmpty() && port.toInt() !in 1..65535) return null
        val valid = when {
            host.startsWith("[") -> host.length > 2 && host.count { it == ':' } >= 2
            host == "localhost" || host.endsWith(".localhost") -> true
            IPV4.matches(host) -> IPV4.matchEntire(host)!!.groupValues.drop(1).all { it.toInt() <= 255 }
            else -> isDomain(host)
        }
        return host.takeIf { valid }
    }

    private fun isDomain(host: String): Boolean {
        val labels = host.removeSuffix(".").split('.')
        if (labels.size < 2) return false
        return labels.all { it.length <= 63 && DOMAIN_LABEL.matches(it) } && TOP_LEVEL_DOMAIN.matches(labels.last())
    }

    /** localhost, loopback IPv6 and private / loopback IPv4 ranges are served over plain http. */
    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host == "[::1]") return true
        val octets = IPV4.matchEntire(host)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
        val (a, b) = octets
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
