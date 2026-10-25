package app.snapcast.proxy.http

/**
 * The first byte range of a `Range: bytes=...` request header (RFC 9110 section 14.1.2). Multi-range
 * requests are answered with their first range only, which every media client accepts.
 */
internal sealed interface ByteRange {
    /** `bytes=first-last`, or `bytes=first-` when [last] is null. */
    data class FromTo(val first: Long, val last: Long?) : ByteRange {
        init {
            require(first >= 0 && (last == null || last >= first)) { "Invalid range $first-$last" }
        }

        override val headerValue: String get() = "bytes=$first-${last ?: ""}"

        override fun resolve(total: Long): ResolvedRange? {
            if (first >= total) return null
            val end = last?.coerceAtMost(total - 1) ?: (total - 1)
            return ResolvedRange(first, end, total)
        }
    }

    /** `bytes=-length`: the last [length] bytes. */
    data class Suffix(val length: Long) : ByteRange {
        init {
            require(length >= 0) { "Invalid suffix length $length" }
        }

        override val headerValue: String get() = "bytes=-$length"

        override fun resolve(total: Long): ResolvedRange? {
            if (length == 0L || total <= 0L) return null
            return ResolvedRange((total - length).coerceAtLeast(0), total - 1, total)
        }
    }

    /** Value for a `Range` request header selecting this range. */
    val headerValue: String

    /** The concrete bytes this range selects in a representation of [total] bytes, or null when unsatisfiable. */
    fun resolve(total: Long): ResolvedRange?

    companion object {
        /** Parses a Range header; null when absent, not in bytes or syntactically invalid (the header is then ignored). */
        fun parse(header: String?): ByteRange? {
            val value = header?.trim() ?: return null
            val eq = value.indexOf('=')
            if (eq < 0 || !value.substring(0, eq).trim().equals("bytes", ignoreCase = true)) return null
            val first = value.substring(eq + 1).split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            val dash = first.indexOf('-')
            if (dash < 0) return null
            val from = first.substring(0, dash).trim()
            val to = first.substring(dash + 1).trim()
            if (from.isEmpty()) return parseNumber(to)?.let { Suffix(it) }
            val start = parseNumber(from) ?: return null
            if (to.isEmpty()) return FromTo(start, null)
            val end = parseNumber(to) ?: return null
            return if (end < start) null else FromTo(start, end)
        }

        private fun parseNumber(text: String): Long? {
            if (text.isEmpty() || text.length > 18 || !text.all { it in '0'..'9' }) return null
            return text.toLong()
        }
    }
}

/** Inclusive byte positions [start]..[end] of a representation of [total] bytes. */
internal data class ResolvedRange(val start: Long, val end: Long, val total: Long) {
    val length: Long get() = end - start + 1

    /** Value of the `Content-Range` response header. */
    val contentRange: String get() = "bytes $start-$end/$total"
}

/**
 * A parsed `Content-Range` response header: `bytes start-end/total`, `bytes start-end/ *` or `bytes * /total`
 * (without the spaces). Unknown parts are null.
 */
internal data class ContentRange(val start: Long?, val end: Long?, val total: Long?) {
    companion object {
        fun parse(header: String?): ContentRange? {
            val value = header?.trim() ?: return null
            if (!value.startsWith("bytes", ignoreCase = true)) return null
            val spec = value.substring(5).trim().removePrefix("=").trim()
            val slash = spec.indexOf('/')
            if (slash < 0) return null
            val rangePart = spec.substring(0, slash).trim()
            val totalPart = spec.substring(slash + 1).trim()
            val total = if (totalPart == "*") null else totalPart.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            if (rangePart == "*") return ContentRange(null, null, total)
            val dash = rangePart.indexOf('-')
            if (dash < 0) return null
            val start = rangePart.substring(0, dash).trim().toLongOrNull() ?: return null
            val end = rangePart.substring(dash + 1).trim().toLongOrNull() ?: return null
            if (start < 0 || end < start) return null
            return ContentRange(start, end, total)
        }
    }
}
