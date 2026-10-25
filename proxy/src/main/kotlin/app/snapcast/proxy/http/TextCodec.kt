package app.snapcast.proxy.http

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Charset decisions for text bodies the proxy rewrites or converts. */
internal object TextCodec {
    /** A decoded text and the charset to encode its rewritten form with. */
    data class Decoded(val text: String, val charset: Charset)

    /**
     * HLS playlists are UTF-8 by specification. A declared charset is honoured; a body that is not valid
     * UTF-8 is read as ISO-8859-1 and written back the same way, so its bytes survive the rewrite unchanged.
     */
    fun decodePlaylist(bytes: ByteArray, declared: Charset?): Decoded {
        if (declared != null) return Decoded(String(bytes, declared), declared)
        return strictUtf8(bytes)?.let { Decoded(it, Charsets.UTF_8) } ?: Decoded(String(bytes, Charsets.ISO_8859_1), Charsets.ISO_8859_1)
    }

    /**
     * Subtitle files come in any encoding: a declared charset or a UTF-16 byte order mark wins, then UTF-8 when the
     * bytes are valid UTF-8, then Windows-1252 (the usual encoding of legacy SRT files).
     */
    fun decodeSubtitle(bytes: ByteArray, declared: Charset?): String {
        if (declared != null) return String(bytes, declared)
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            if ((b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE)) return String(bytes, Charsets.UTF_16)
        }
        return strictUtf8(bytes) ?: String(bytes, legacyCharset())
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun legacyCharset(): Charset = try {
        Charset.forName("windows-1252")
    } catch (e: IllegalArgumentException) {
        Charsets.ISO_8859_1
    }
}
