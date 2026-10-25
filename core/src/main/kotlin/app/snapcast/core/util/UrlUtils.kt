package app.snapcast.core.util

import java.security.MessageDigest

/** URL helpers that work on plain strings (no android.net.Uri) so they are JVM-testable. */
object UrlUtils {
    /** Query parameters that only select a byte range or defeat caches; dropped by [normalizeForDedupe]. */
    private val VOLATILE_PARAMS = setOf("range", "bytestart", "byteend", "rbuf", "rn", "_")

    /** Cache-buster parameters dropped only when their value is purely numeric (timestamps, counters). */
    private val NUMERIC_CACHE_BUSTERS = setOf("t", "ts", "cb")

    private val DIGITS = Regex("\\d+")
    private val EXTENSION = Regex("[a-z0-9]+")

    /** Lower-cased host of an absolute http(s) URL, or null. */
    fun hostOf(url: String): String? {
        val ref = UriReference.parse(url.trim())
        if (!isHttpScheme(ref.scheme)) return null
        return ref.host?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    /** True if [host] equals [domain] or is a subdomain of it (case-insensitive, ignores leading "www."). */
    fun hostMatches(host: String, domain: String): Boolean {
        val h = normalizeHost(host)
        val d = normalizeHost(domain)
        if (h.isEmpty() || d.isEmpty()) return false
        return h == d || h.endsWith(".$d")
    }

    private fun normalizeHost(value: String): String =
        value.trim().lowercase().trimEnd('.').removePrefix("www.")

    /**
     * Resolves [reference] against [base] (RFC 3986 section 5.2), returning an absolute URL.
     * Absolute references pass through (dot segments of hierarchical ones are removed, as the RFC requires;
     * opaque ones such as `data:` are returned unchanged). Characters are never re-encoded.
     */
    fun resolve(base: String, reference: String): String {
        val r = UriReference.parse(reference.trim())
        if (r.scheme != null) {
            return if (r.isOpaque) r.toString() else r.copy(path = UriReference.removeDotSegments(r.path)).toString()
        }
        val b = UriReference.parse(base.trim())
        val target = when {
            r.authority != null -> UriReference(b.scheme, r.authority, UriReference.removeDotSegments(r.path), r.query, r.fragment)
            r.path.isEmpty() -> UriReference(b.scheme, b.authority, b.path, r.query ?: b.query, r.fragment)
            r.path.startsWith("/") -> UriReference(b.scheme, b.authority, UriReference.removeDotSegments(r.path), r.query, r.fragment)
            else -> UriReference(
                b.scheme,
                b.authority,
                UriReference.removeDotSegments(UriReference.merge(b, r.path)),
                r.query,
                r.fragment,
            )
        }
        return target.toString()
    }

    /**
     * Canonical form used for de-duplication: drops the fragment and volatile byte-range / cache-busting
     * query parameters (range, bytestart, byteend, _, t when numeric-only cache busters), lower-cases scheme and host.
     *
     * Also drops default ports (80 for http, 443 for https), the rbuf/rn parameters and numeric ts/cb
     * parameters, and uses "/" for an empty http(s) path. The order of the remaining parameters is kept.
     */
    fun normalizeForDedupe(url: String): String {
        val ref = UriReference.parse(url.trim())
        val scheme = ref.scheme?.lowercase()
        val authority = ref.authority?.let { normalizeAuthority(ref, scheme) }
        val path = if (isHttpScheme(scheme) && authority != null && ref.path.isEmpty()) "/" else ref.path
        val query = ref.query?.let(::filterQuery)
        return UriReference(scheme, authority, path, query, fragment = null).toString()
    }

    private fun normalizeAuthority(ref: UriReference, scheme: String?): String {
        val host = ref.host.orEmpty().lowercase()
        val port = ref.port
        val keepPort = when {
            port.isNullOrEmpty() -> false
            scheme == "http" && port == "80" -> false
            scheme == "https" && port == "443" -> false
            else -> true
        }
        return ref.userInfoPrefix + host + if (keepPort) ":$port" else ""
    }

    private fun filterQuery(query: String): String? {
        val kept = query.split('&').filter { param ->
            if (param.isEmpty()) return@filter false
            val name = param.substringBefore('=').lowercase()
            val value = param.substringAfter('=', "")
            when (name) {
                in VOLATILE_PARAMS -> false
                in NUMERIC_CACHE_BUSTERS -> !DIGITS.matches(value)
                else -> true
            }
        }
        return if (kept.isEmpty()) null else kept.joinToString("&")
    }

    /** Short stable id (hex, 16 chars) derived from [normalizeForDedupe] of [url]. */
    fun stableId(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizeForDedupe(url).toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xff
            hex.append(HEX[b ushr 4]).append(HEX[b and 0x0f])
        }
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"

    /** Path extension without dot, lower-cased ("m3u8"), ignoring query and fragment; null when none. */
    fun extensionOf(url: String): String? {
        val ref = UriReference.parse(url.trim())
        if (ref.isOpaque) return null
        val name = ref.lastSegment
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        val ext = name.substring(dot + 1).lowercase()
        return ext.takeIf { EXTENSION.matches(it) }
    }

    /** Last path segment decoded (for display / file-name hints), or null. */
    fun fileNameOf(url: String): String? {
        val ref = UriReference.parse(url.trim())
        if (ref.isOpaque) return null
        return percentDecode(ref.lastSegment).takeIf { it.isNotBlank() }
    }

    fun isHttp(url: String): Boolean = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun isHttpScheme(scheme: String?): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
}
