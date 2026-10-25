package app.snapcast.browser.download

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Locale

/** File names and MIME types for downloads (pure string logic, JVM-testable). */
internal object DownloadFileNames {
    private const val MAX_NAME_LENGTH = 120
    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f\\u007f]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * File name from a Content-Disposition header: RFC 5987/6266 `filename*` (percent-encoded with a
     * charset) wins over plain `filename`, which may be a quoted string or a bare token.
     */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val params = parameters(header)
        val extended = params["filename*"]?.let(::decodeExtendedValue)
        val plain = params["filename"]
        return sanitize(extended) ?: sanitize(plain)
    }

    /** Makes [name] safe as a single path segment; null when nothing usable remains. */
    fun sanitize(name: String?): String? {
        if (name == null) return null
        val base = name.replace('\\', '/').substringAfterLast('/')
        val cleaned = base.replace(WHITESPACE, " ")
            .replace(ILLEGAL_CHARS, "_")
            .trim()
            .trim('.', ' ')
        if (cleaned.isEmpty() || cleaned.all { it == '_' }) return null
        return truncate(cleaned)
    }

    /** `type/subtype` in lower case without parameters, or null for blank or malformed values. */
    fun mimeTypeOf(contentType: String?): String? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        return type.takeIf { it.contains('/') && !it.startsWith('/') && !it.endsWith('/') }
    }

    /** Lower-cased extension of [name] without the dot, or null. */
    fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** Parses a Content-Length header; null when missing or not a non-negative number. */
    fun contentLengthOf(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    /** "name.ext" -> "name (n).ext" for de-duplicating files in a directory. */
    fun numbered(name: String, n: Int): String {
        val ext = extensionOf(name)
        return if (ext == null) "$name ($n)" else "${name.dropLast(ext.length + 1)} ($n).$ext"
    }

    private fun truncate(name: String): String {
        if (name.length <= MAX_NAME_LENGTH) return name
        val ext = extensionOf(name)?.takeIf { it.length <= 16 }
        return if (ext == null) {
            name.take(MAX_NAME_LENGTH)
        } else {
            name.dropLast(ext.length + 1).take(MAX_NAME_LENGTH - ext.length - 1) + "." + ext
        }
    }

    /** `attachment; filename="a;b.txt"; size=3` -> {filename=a;b.txt, size=3} (names lower-cased). */
    private fun parameters(header: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val firstSemicolon = header.indexOf(';')
        val dispositionType = if (firstSemicolon < 0) header else header.substring(0, firstSemicolon)
        // Tolerate servers that omit the disposition type ("filename=a.pdf").
        var i = when {
            dispositionType.contains('=') -> 0
            firstSemicolon < 0 -> return result
            else -> firstSemicolon + 1
        }
        while (i < header.length) {
            while (i < header.length && (header[i] == ' ' || header[i] == '\t' || header[i] == ';')) i++
            val nameStart = i
            while (i < header.length && header[i] != '=' && header[i] != ';') i++
            val name = header.substring(nameStart, i).trim().lowercase(Locale.ROOT)
            if (i >= header.length || header[i] == ';') {
                continue
            }
            i++ // '='
            while (i < header.length && (header[i] == ' ' || header[i] == '\t')) i++
            val value: String
            if (i < header.length && header[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < header.length && header[i] != '"') {
                    if (header[i] == '\\' && i + 1 < header.length) i++
                    sb.append(header[i])
                    i++
                }
                i++ // closing quote
                value = sb.toString()
                while (i < header.length && header[i] != ';') i++
            } else {
                val valueStart = i
                while (i < header.length && header[i] != ';') i++
                value = header.substring(valueStart, i).trim()
            }
            if (name.isNotEmpty() && name !in result) result[name] = value
        }
        return result
    }

    /** `UTF-8'en'%E2%82%AC%20rates.txt` -> "€ rates.txt"; null for malformed values. */
    private fun decodeExtendedValue(value: String): String? {
        val first = value.indexOf('\'')
        val second = if (first >= 0) value.indexOf('\'', first + 1) else -1
        if (first < 0 || second < 0) return null
        val charset = try {
            Charset.forName(value.substring(0, first).trim().ifEmpty { "UTF-8" })
        } catch (e: IllegalArgumentException) {
            return null
        }
        return percentDecode(value.substring(second + 1), charset)
    }

    private fun percentDecode(text: String, charset: Charset): String? {
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val high = Character.digit(text[i + 1], 16)
                val low = Character.digit(text[i + 2], 16)
                if (high < 0 || low < 0) return null
                bytes.write(high * 16 + low)
                i += 3
            } else {
                val encoded = c.toString().toByteArray(charset)
                bytes.write(encoded, 0, encoded.size)
                i++
            }
        }
        return String(bytes.toByteArray(), charset)
    }
}
