package app.snapcast.ui.local

import app.snapcast.core.media.M3uEntry
import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.model.StreamType
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** The real kind of a channel stream, found from the first bytes of its response. */
internal data class ChannelKind(val type: StreamType, val mimeType: String?, val live: Boolean)

/**
 * Finds what a channel URL that does not name its format (`http://host:port/user/pass/12345`, `play.php?id=`)
 * actually serves. IPTV panels use such URLs for both HLS playlists and raw MPEG-TS, and guessing wrong
 * makes the phone player and the Cast receiver fail, so the start of the body decides.
 */
@Singleton
internal class ChannelProbe @Inject constructor(private val client: OkHttpClient) {

    private val probeClient by lazy { client.newBuilder().callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).build() }

    /** Recent results by URL, so playing a channel and then casting it does not probe twice. */
    private val cache = object : LinkedHashMap<String, ChannelKind>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChannelKind>?): Boolean =
            size > CACHE_SIZE
    }

    /**
     * The kind of [channel]'s stream, or null when its URL already tells the type or the probe failed (the
     * caller then keeps the URL-based guess, [IptvChannels.streamType]).
     */
    suspend fun resolve(channel: M3uEntry): ChannelKind? {
        val url = channel.url
        if (MediaClassifier.typeFromUrl(url) != null) return null
        synchronized(cache) { cache[url] }?.let { return it }
        val kind = withContext(Dispatchers.IO) { probe(channel) } ?: return null
        synchronized(cache) { cache[url] = kind }
        return kind
    }

    private fun probe(channel: M3uEntry): ChannelKind? {
        val builder = try {
            Request.Builder().url(channel.url)
        } catch (e: IllegalArgumentException) {
            return null
        }
        for ((name, value) in IptvChannels.headers(channel).toMap()) {
            try {
                builder.header(name, value)
            } catch (e: IllegalArgumentException) {
                // OkHttp rejects non-ASCII header values: probe without that header.
            }
        }
        // No Range header: some live endpoints answer 400/416 to it. Only PROBE_BYTES are read, then the
        // connection is closed.
        return try {
            probeClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val head = response.body.byteStream().use { readUpTo(it, PROBE_BYTES) }
                classify(head, response.header("Content-Type"), response.body.contentLength().takeIf { it >= 0 })
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        private const val PROBE_BYTES = 2048
        private const val PROBE_TIMEOUT_S = 8L
        private const val CACHE_SIZE = 128
        private const val TS_PACKET = 188
        private const val TS_SYNC: Byte = 0x47

        /**
         * Kind of a stream from the first bytes of its body, then its Content-Type. The body comes first:
         * IPTV servers often send generic or wrong Content-Type headers.
         */
        internal fun classify(head: ByteArray, contentType: String?, contentLength: Long?): ChannelKind {
            val type = MediaClassifier.sniffPlaylist(String(head, Charsets.UTF_8))
                ?: when {
                    isMpegTs(head) -> StreamType.TS
                    isIsoMedia(head) -> StreamType.MP4
                    else -> null
                }
                ?: MediaClassifier.typeFromContentType(contentType)
                ?: StreamType.OTHER
            val mime = when (type) {
                // The body is MPEG-TS whatever the header says.
                StreamType.TS -> StreamType.TS.defaultMimeType
                // The type alone picks the manifest MIME types.
                StreamType.HLS, StreamType.DASH -> null
                else -> concreteMediaMime(contentType)
            }
            val live = when (type) {
                StreamType.HLS -> true
                StreamType.DASH -> false
                else -> contentLength == null
            }
            return ChannelKind(type, mime, live)
        }

        /**
         * MPEG-TS: 188-byte packets that each start with the 0x47 sync byte. The body may start inside a
         * packet, so the first packet boundary is searched for.
         */
        private fun isMpegTs(head: ByteArray): Boolean {
            val starts = minOf(TS_PACKET, head.size)
            for (start in 0 until starts) {
                if (head[start] != TS_SYNC) continue
                // Up to three following packets must start with the sync byte too; a body too short to hold
                // a second packet is not a live stream.
                val following = minOf(3, (head.size - start - 1) / TS_PACKET)
                if (following > 0 && (1..following).all { head[start + it * TS_PACKET] == TS_SYNC }) return true
            }
            return false
        }

        /** MP4/ISO BMFF: the first box is `ftyp` (or `styp` for a fragmented stream). */
        private fun isIsoMedia(head: ByteArray): Boolean {
            if (head.size < 8) return false
            val box = String(head, 4, 4, Charsets.ISO_8859_1)
            return box == "ftyp" || box == "styp"
        }

        private fun concreteMediaMime(contentType: String?): String? {
            val mime = contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return mime.takeIf { it.startsWith("video/", ignoreCase = true) || it.startsWith("audio/", ignoreCase = true) }
        }

        /** Up to [max] bytes of [input] (fewer at end of stream). */
        private fun readUpTo(input: InputStream, max: Int): ByteArray {
            val buffer = ByteArray(max)
            var filled = 0
            while (filled < max) {
                val read = input.read(buffer, filled, max - filled)
                if (read < 0) break
                filled += read
            }
            return if (filled == max) buffer else buffer.copyOf(filled)
        }
    }
}
