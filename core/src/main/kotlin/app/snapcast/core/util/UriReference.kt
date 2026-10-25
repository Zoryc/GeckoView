package app.snapcast.core.util

import java.io.ByteArrayOutputStream

/**
 * A URI reference split into its five RFC 3986 components (section 3). Parsing never fails and
 * never validates or encodes characters, so real-world URLs containing spaces, `|` or other
 * characters that `java.net.URI` rejects survive untouched.
 *
 * `null` means "component not present", which is distinct from present-but-empty
 * (e.g. `http://a/b?` has an empty query, `http://a/b` has none).
 */
internal data class UriReference(
    val scheme: String?,
    val authority: String?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    /** Recomposes the components (RFC 3986 section 5.3). */
    override fun toString(): String = buildString {
        if (scheme != null) append(scheme).append(':')
        if (authority != null) append("//").append(authority)
        append(path)
        if (query != null) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    }

    /** Host part of the authority (no user info, no port), as written. Null without authority. */
    val host: String?
        get() {
            val hostPort = authority?.substringAfterLast('@') ?: return null
            return if (hostPort.startsWith('[')) {
                val end = hostPort.indexOf(']')
                if (end < 0) hostPort else hostPort.substring(0, end + 1)
            } else {
                hostPort.substringBefore(':')
            }
        }

    /** Port part of the authority as written ("" for "host:"), or null when absent. */
    val port: String?
        get() {
            val hostPort = authority?.substringAfterLast('@') ?: return null
            val afterHost = if (hostPort.startsWith('[')) {
                val end = hostPort.indexOf(']')
                if (end < 0) return null else hostPort.substring(end + 1)
            } else {
                val colon = hostPort.indexOf(':')
                if (colon < 0) return null else hostPort.substring(colon)
            }
            return if (afterHost.startsWith(':')) afterHost.substring(1) else null
        }

    /** User info including the trailing "@", or "" when absent. */
    val userInfoPrefix: String
        get() {
            val auth = authority ?: return ""
            val at = auth.lastIndexOf('@')
            return if (at < 0) "" else auth.substring(0, at + 1)
        }

    /**
     * True for references like `data:...`, `blob:...`, `about:blank` or `mailto:...`: they have a scheme
     * but no authority and their path is not hierarchical.
     */
    val isOpaque: Boolean get() = scheme != null && authority == null && !path.startsWith("/")

    /** Last path segment without matrix parameters (";jsessionid=..."), still percent-encoded. */
    val lastSegment: String
        get() = path.substringAfterLast('/').substringBefore(';')

    companion object {
        private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.\\-]*")

        /** Splits [value] following the RFC 3986 Appendix B grammar, accepting only well-formed schemes. */
        fun parse(value: String): UriReference {
            var rest = value
            var fragment: String? = null
            val hash = rest.indexOf('#')
            if (hash >= 0) {
                fragment = rest.substring(hash + 1)
                rest = rest.substring(0, hash)
            }
            var query: String? = null
            val question = rest.indexOf('?')
            if (question >= 0) {
                query = rest.substring(question + 1)
                rest = rest.substring(0, question)
            }
            var scheme: String? = null
            val colon = rest.indexOf(':')
            if (colon > 0) {
                val candidate = rest.substring(0, colon)
                if (SCHEME.matches(candidate)) {
                    scheme = candidate
                    rest = rest.substring(colon + 1)
                }
            }
            var authority: String? = null
            if (rest.startsWith("//")) {
                val end = rest.indexOf('/', 2).let { if (it < 0) rest.length else it }
                authority = rest.substring(2, end)
                rest = rest.substring(end)
            }
            return UriReference(scheme, authority, rest, query, fragment)
        }

        /** RFC 3986 section 5.2.4. */
        fun removeDotSegments(path: String): String {
            if (!path.contains('.')) return path
            var input = path
            val output = StringBuilder()
            while (input.isNotEmpty()) {
                when {
                    input.startsWith("../") -> input = input.substring(3)
                    input.startsWith("./") -> input = input.substring(2)
                    input.startsWith("/./") -> input = input.substring(2)
                    input == "/." -> input = "/"
                    input.startsWith("/../") -> {
                        input = input.substring(3)
                        removeLastSegment(output)
                    }
                    input == "/.." -> {
                        input = "/"
                        removeLastSegment(output)
                    }
                    input == "." || input == ".." -> input = ""
                    else -> {
                        val start = if (input.startsWith('/')) 1 else 0
                        val next = input.indexOf('/', start).let { if (it < 0) input.length else it }
                        output.append(input, 0, next)
                        input = input.substring(next)
                    }
                }
            }
            return output.toString()
        }

        private fun removeLastSegment(output: StringBuilder) {
            val last = output.lastIndexOf("/")
            output.setLength(if (last < 0) 0 else last)
        }

        /** RFC 3986 section 5.2.3. */
        fun merge(base: UriReference, referencePath: String): String =
            if (base.authority != null && base.path.isEmpty()) {
                "/$referencePath"
            } else {
                val slash = base.path.lastIndexOf('/')
                if (slash < 0) referencePath else base.path.substring(0, slash + 1) + referencePath
            }
    }
}

/** Decodes %XX escapes as UTF-8, leaving malformed escapes and "+" untouched (path semantics). */
internal fun percentDecode(value: String): String {
    if (!value.contains('%')) return value
    val bytes = ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val hi = Character.digit(value[i + 1], 16)
            val lo = Character.digit(value[i + 2], 16)
            if (hi >= 0 && lo >= 0) {
                bytes.write(hi * 16 + lo)
                i += 3
                continue
            }
        }
        val width = if (Character.isHighSurrogate(c) && i + 1 < value.length) 2 else 1
        bytes.write(value.substring(i, i + width).toByteArray(Charsets.UTF_8))
        i += width
    }
    return bytes.toString(Charsets.UTF_8.name())
}
