package app.snapcast.proxy.http

import java.util.Base64

/** Percent-encoding helpers for request paths. */
internal object PathCodec {
    private const val HEX = "0123456789ABCDEF"

    /** RFC 3986 `pchar` minus `%`, plus `/`: unreserved, sub-delims, ':' and '@'. */
    private fun isPathSafe(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~!$&'()*+,;=:@/"

    /**
     * Re-encodes a percent-DECODED path (NanoHTTPD decodes `IHTTPSession.getUri()`) so it can be appended to an
     * upstream URL: characters that are not allowed in a path are UTF-8 percent-encoded, `/` stays a separator.
     */
    fun encodePath(decoded: String): String {
        if (decoded.all(::isPathSafe)) return decoded
        val out = StringBuilder(decoded.length + 16)
        var i = 0
        while (i < decoded.length) {
            val c = decoded[i]
            if (isPathSafe(c)) {
                out.append(c)
                i++
                continue
            }
            // Encode a whole code point so surrogate pairs become one 4-byte UTF-8 sequence.
            val width = if (Character.isHighSurrogate(c) && i + 1 < decoded.length) 2 else 1
            for (byte in decoded.substring(i, i + width).toByteArray(Charsets.UTF_8)) {
                val b = byte.toInt() and 0xff
                out.append('%').append(HEX[b ushr 4]).append(HEX[b and 0x0f])
            }
            i += width
        }
        return out.toString()
    }

    /**
     * Encodes literal `+` in the path part of [reference] as `%2B`. NanoHTTPD decodes request paths with
     * `URLDecoder`, which would turn a literal `+` into a space; `%2B` decodes back to `+`. The query is
     * read raw, so it is left alone.
     */
    fun protectPlusInPath(reference: String): String {
        val pathEnd = reference.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) reference.length else it }
        val path = reference.substring(0, pathEnd)
        if (!path.contains('+')) return reference
        return path.replace("+", "%2B") + reference.substring(pathEnd)
    }
}

/** URL-safe Base64 without padding, as used in proxy routes. */
internal object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun encode(text: String): String = encode(text.toByteArray(Charsets.UTF_8))

    /** Decoded UTF-8 text, or null when [value] is not valid base64url. */
    fun decodeToString(value: String): String? = try {
        String(decoder.decode(value), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }
}
