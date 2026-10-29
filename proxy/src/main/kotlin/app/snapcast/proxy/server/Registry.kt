package app.snapcast.proxy.server

import app.snapcast.proxy.LocalSource
import app.snapcast.proxy.ProxyStreamRequest
import app.snapcast.proxy.SubtitleSource
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Something the proxy serves under a random id. */
internal sealed interface Registration {
    class Stream(val request: ProxyStreamRequest, val policy: HeaderPolicy) : Registration {
        /** EXT-X-DEFINE variables of the stream's main playlist, which its media playlists may IMPORT. */
        @Volatile
        var mainVariables: Map<String, String> = emptyMap()
    }

    class Local(val source: LocalSource) : Registration

    /** [policy] holds the request headers of a [SubtitleSource.Remote]. */
    class Subtitle(val source: SubtitleSource, val policy: HeaderPolicy?) : Registration
}

/** Thread-safe id → [Registration] map. Ids are 128-bit random values in hex. */
internal class Registry {
    private val entries = ConcurrentHashMap<String, Registration>()
    private val random = SecureRandom()

    /** Stores [registration] under a fresh id and returns it. */
    fun add(registration: Registration): String {
        while (true) {
            val id = newId()
            if (entries.putIfAbsent(id, registration) == null) return id
        }
    }

    fun stream(id: String?): Registration.Stream? = id?.let { entries[it] } as? Registration.Stream

    fun local(id: String?): Registration.Local? = id?.let { entries[it] } as? Registration.Local

    fun subtitle(id: String?): Registration.Subtitle? = id?.let { entries[it] } as? Registration.Subtitle

    fun remove(id: String) {
        entries.remove(id)
    }

    fun clear() = entries.clear()

    private fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return buildString(32) {
            for (byte in bytes) {
                val b = byte.toInt() and 0xff
                append(HEX[b ushr 4]).append(HEX[b and 0x0f])
            }
        }
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
