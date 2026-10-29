package app.snapcast.core.media

import java.net.URLDecoder

/**
 * EXT-X-DEFINE variables (RFC 8216bis 4.4.2.3) and their `{$name}` references, handled the lenient way players
 * do: the first definition of a name wins and a reference to an unknown name stays as written. A QUERYPARAM
 * variable takes its value from the playlist URL's query, an IMPORT one from the multivariant playlist's
 * variables. Values that would break a quoted attribute or a line (a quote, a line break) are not used.
 */
object HlsVariables {
    private const val DEFINE = "#EXT-X-DEFINE:"
    private const val REFERENCE_START = "{$"

    /** The variables the playlist [text] defines, by name. Empty when it defines none. */
    fun defined(text: String, playlistUrl: String, imported: Map<String, String> = emptyMap()): Map<String, String> {
        if (!text.contains(DEFINE, ignoreCase = true)) return emptyMap()
        return defined(text.lineSequence(), playlistUrl, imported)
    }

    /** The variables the playlist [lines] define, by name. */
    fun defined(lines: Sequence<String>, playlistUrl: String, imported: Map<String, String> = emptyMap()): Map<String, String> {
        val variables = LinkedHashMap<String, String>()
        for (line in lines) {
            val define = define(line.trim(), playlistUrl, imported) ?: continue
            if (define.value != null) variables.putIfAbsent(define.name, define.value)
        }
        return variables
    }

    /** [line] with its references to [variables] replaced. EXT-X-DEFINE lines are left as they are. */
    fun substitute(line: String, variables: Map<String, String>): String {
        if (variables.isEmpty() || !line.contains(REFERENCE_START) || isDefine(line.trimStart())) return line
        // A plain scan rather than a regex: Android's ICU engine and the JVM's disagree on braces.
        val out = StringBuilder(line.length + 32)
        var pos = 0
        while (true) {
            val start = line.indexOf(REFERENCE_START, pos)
            if (start < 0) break
            val end = line.indexOf('}', start + REFERENCE_START.length)
            if (end < 0) break
            val value = variables[line.substring(start + REFERENCE_START.length, end)]
            if (value == null) {
                // Not a defined variable: keep "{$" and look for a reference after it.
                out.append(line, pos, start + REFERENCE_START.length)
                pos = start + REFERENCE_START.length
            } else {
                out.append(line, pos, start).append(value)
                pos = end + 1
            }
        }
        return out.append(line, pos, line.length).toString()
    }

    /**
     * The playlist [text] with its references substituted and its QUERYPARAM and IMPORT definitions turned into
     * NAME/VALUE ones, for a player that reads it from another URL (the proxy's) or without the multivariant
     * playlist. The same instance when it defines nothing.
     */
    fun resolve(text: String, playlistUrl: String, imported: Map<String, String> = emptyMap()): String {
        val variables = defined(text, playlistUrl, imported)
        if (variables.isEmpty()) return text
        val out = StringBuilder(text.length + 64)
        var pos = 0
        while (pos < text.length) {
            val newline = text.indexOf('\n', pos)
            val end = if (newline < 0) text.length else newline + 1
            val contentEnd = if (newline < 0) end else if (newline > pos && text[newline - 1] == '\r') newline - 1 else newline
            val line = text.substring(pos, contentEnd)
            val define = define(line.trim(), playlistUrl, imported)
            out.append(
                when {
                    define == null -> substitute(line, variables)
                    define.kind != Kind.VALUE && define.value != null -> "#EXT-X-DEFINE:NAME=\"${define.name}\",VALUE=\"${define.value}\""
                    else -> line
                },
            )
            out.append(text, contentEnd, end)
            pos = end
        }
        return out.toString()
    }

    private enum class Kind { VALUE, QUERYPARAM, IMPORT }

    /** One EXT-X-DEFINE: the variable it defines and its value, null when it cannot be used. */
    private class Define(val kind: Kind, val name: String, val value: String?)

    private fun isDefine(line: String): Boolean = line.startsWith(DEFINE, ignoreCase = true)

    /** Variable names are made of letters, digits, "-" and "_". */
    private fun isName(name: String): Boolean =
        name.isNotEmpty() && name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    private fun define(line: String, playlistUrl: String, imported: Map<String, String>): Define? {
        if (!isDefine(line)) return null
        val attributes = HlsParser.parseAttributes(line.substring(DEFINE.length))
        val define = attributes["NAME"]?.let { Define(Kind.VALUE, it, attributes["VALUE"]) }
            ?: attributes["QUERYPARAM"]?.let { Define(Kind.QUERYPARAM, it, queryParameter(playlistUrl, it)) }
            ?: attributes["IMPORT"]?.let { Define(Kind.IMPORT, it, imported[it]) }
            ?: return null
        if (!isName(define.name)) return null
        val usable = define.value?.takeUnless { value -> value.any { it == '"' || it == '\r' || it == '\n' } }
        return if (usable === define.value) define else Define(define.kind, define.name, null)
    }

    /** The decoded value of the first [name] parameter in [url]'s query, as players read it. */
    private fun queryParameter(url: String, name: String): String? {
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            if (decode(pair.substringBefore('=')) == name) return decode(pair.substringAfter('=', ""))
        }
        return null
    }

    private fun decode(text: String): String =
        try {
            URLDecoder.decode(text, "UTF-8")
        } catch (_: IllegalArgumentException) {
            text
        }
}
