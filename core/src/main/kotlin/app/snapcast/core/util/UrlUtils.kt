package app.snapcast.core.util

/** URL helpers that work on plain strings (no android.net.Uri) so they are JVM-testable. */
object UrlUtils {
    /** Lower-cased host of an absolute http(s) URL, or null. */
    fun hostOf(url: String): String? = TODO()

    /** True if [host] equals [domain] or is a subdomain of it (case-insensitive, ignores leading "www."). */
    fun hostMatches(host: String, domain: String): Boolean = TODO()

    /** Resolves [reference] against [base] (RFC 3986), returning an absolute URL. Absolute references pass through. */
    fun resolve(base: String, reference: String): String = TODO()

    /**
     * Canonical form used for de-duplication: drops the fragment and volatile byte-range / cache-busting
     * query parameters (range, bytestart, byteend, _, t when numeric-only cache busters), lower-cases scheme and host.
     */
    fun normalizeForDedupe(url: String): String = TODO()

    /** Short stable id (hex, 16 chars) derived from [normalizeForDedupe] of [url]. */
    fun stableId(url: String): String = TODO()

    /** Path extension without dot, lower-cased ("m3u8"), ignoring query and fragment; null when none. */
    fun extensionOf(url: String): String? = TODO()

    /** Last path segment decoded (for display / file-name hints), or null. */
    fun fileNameOf(url: String): String? = TODO()

    fun isHttp(url: String): Boolean = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}
