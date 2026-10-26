package app.snapcast.core.util

import java.security.MessageDigest

/** URL helpers that work on plain strings (no android.net.Uri) so they are JVM-testable. */
object UrlUtils {
    /**
     * Query parameters that only select a byte range or defeat caches; dropped by [normalizeForDedupe].
     * LL-HLS delivery directives (any parameter starting with [HLS_DIRECTIVE_PREFIX]) are dropped as well.
     */
    private val VOLATILE_PARAMS = setOf("range", "bytestart", "byteend", "rbuf", "rn", "_")

    /**
     * Prefix that RFC 8216bis (section 6.2.5) reserves for Low-Latency HLS delivery directives such as
     * `_HLS_msn`, `_HLS_part` and `_HLS_skip`. Players add them to every blocking playlist reload; they
     * never select a different resource. Matched case-insensitively.
     */
    private const val HLS_DIRECTIVE_PREFIX = "_hls_"

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
     * Also drops default ports (80 for http, 443 for https), the rbuf/rn parameters, numeric ts/cb
     * parameters and LL-HLS delivery directives (`_HLS_msn`, `_HLS_part`, `_HLS_skip` and any other
     * `_HLS_*` parameter, case-insensitive), and uses "/" for an empty http(s) path. The order of the
     * remaining parameters is kept.
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
            when {
                name in VOLATILE_PARAMS -> false
                name.startsWith(HLS_DIRECTIVE_PREFIX) -> false
                name in NUMERIC_CACHE_BUSTERS -> !DIGITS.matches(value)
                else -> true
            }
        }
        return if (kept.isEmpty()) null else kept.joinToString("&")
    }

    /**
     * [url] without its LL-HLS delivery directives (query parameters whose name starts with `_HLS_`,
     * case-insensitive), so a stored playlist URL never replays a stale blocking-reload request.
     * Drops the `?` when no parameter is left. Everything else, including the order and spelling of
     * the other parameters and the fragment, is kept byte for byte; a URL without directives is
     * returned unchanged.
     */
    fun withoutHlsDirectives(url: String): String {
        val hash = url.indexOf('#')
        val beforeFragment = if (hash < 0) url else url.substring(0, hash)
        val question = beforeFragment.indexOf('?')
        if (question < 0) return url
        val params = beforeFragment.substring(question + 1).split('&')
        if (params.none(::isHlsDirective)) return url
        val kept = params.filterNot(::isHlsDirective)
        val fragment = if (hash < 0) "" else url.substring(hash)
        val base = beforeFragment.substring(0, question)
        return if (kept.all { it.isEmpty() }) base + fragment else base + "?" + kept.joinToString("&") + fragment
    }

    private fun isHlsDirective(param: String): Boolean =
        param.substringBefore('=').startsWith(HLS_DIRECTIVE_PREFIX, ignoreCase = true)

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
