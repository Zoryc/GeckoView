package app.snapcast.core.media

/** One entry of an IPTV / extended M3U playlist. */
data class M3uEntry(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val durationSec: Double? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/** Parses simple and extended (#EXTM3U / #EXTINF) M3U playlists used for IPTV lists. */
object M3uParser {
    /** Relative entries are resolved against [baseUrl] when given. Returns empty list for HLS media playlists. */
    fun parse(text: String, baseUrl: String? = null): List<M3uEntry> = TODO()
}
