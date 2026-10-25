package app.snapcast.ui.local

import app.snapcast.core.media.M3uEntry
import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import app.snapcast.core.util.UrlUtils
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/** An imported IPTV playlist: where it came from and its playable (http/https) channels. */
internal data class IptvPlaylist(
    /** File name or URL host shown to the user. */
    val name: String,
    val channels: List<M3uEntry>,
    /** Entries that were dropped because they do not use http(s) (rtmp://, udp://...). */
    val skipped: Int = 0,
)

/** Channels of one `group-title` ([name] null for channels without a group). */
internal data class ChannelGroup(val name: String?, val channels: List<M3uEntry>) {
    /** Stable key used for list items and the collapsed-groups set. */
    val key: String get() = name ?: ""
}

/** Pure IPTV helpers: playlist filtering, grouping, search and conversion to castable videos. */
internal object IptvChannels {
    /** Tab id given to videos built from playlist channels (they belong to no browser tab). */
    const val TAB_ID = "iptv"

    /** Keeps only http(s) entries, which are the ones the Cast receiver and the phone player can open. */
    fun playlist(name: String, entries: List<M3uEntry>): IptvPlaylist {
        val playable = entries.filter { UrlUtils.isHttp(it.url) }
        return IptvPlaylist(name = name, channels = playable, skipped = entries.size - playable.size)
    }

    /**
     * Channels matching [query] (name or group, case-insensitive; blank matches everything) grouped by
     * group-title in order of first appearance; channels without a group come last.
     */
    fun group(channels: List<M3uEntry>, query: String): List<ChannelGroup> {
        val needle = query.trim().lowercase(Locale.ROOT)
        val grouped = LinkedHashMap<String?, MutableList<M3uEntry>>()
        for (channel in channels) {
            if (needle.isNotEmpty() && !matches(channel, needle)) continue
            grouped.getOrPut(channel.group?.trim()?.takeIf { it.isNotEmpty() }) { ArrayList() } += channel
        }
        val named = grouped.filterKeys { it != null }.map { (name, items) -> ChannelGroup(name, items) }
        val ungrouped = grouped[null]?.let { listOf(ChannelGroup(null, it)) }.orEmpty()
        return named + ungrouped
    }

    private fun matches(channel: M3uEntry, needle: String): Boolean =
        channel.name.lowercase(Locale.ROOT).contains(needle) ||
            channel.group?.lowercase(Locale.ROOT)?.contains(needle) == true

    /** Stream type of a channel URL; extension-less URLs (typical for IPTV) are assumed to be HLS. */
    fun streamType(url: String): StreamType =
        MediaClassifier.typeFromUrl(url) ?: if (UrlUtils.extensionOf(url) == null) StreamType.HLS else StreamType.OTHER

    /** Referer / User-Agent from `#EXTVLCOPT:http-referrer=` and `#EXTVLCOPT:http-user-agent=` lines. */
    fun headers(channel: M3uEntry): MediaHeaders {
        val attributes = channel.attributes
        val referer = (attributes["http-referrer"] ?: attributes["http-referer"])?.takeIf { it.isNotBlank() }
        val userAgent = attributes["http-user-agent"]?.takeIf { it.isNotBlank() }
        val origin = attributes["http-origin"]?.takeIf { it.isNotBlank() }
        return MediaHeaders(referer = referer, userAgent = userAgent, origin = origin)
    }

    /** A castable [DetectedVideo] for [channel]. */
    fun toDetectedVideo(channel: M3uEntry, playlistName: String?, nowMs: Long): DetectedVideo {
        val type = streamType(channel.url)
        return DetectedVideo(
            id = UrlUtils.stableId(channel.url),
            tabId = TAB_ID,
            url = channel.url,
            type = type,
            pageTitle = playlistName,
            title = channel.name,
            headers = headers(channel),
            durationSec = channel.durationSec,
            posterUrl = channel.logoUrl?.takeIf { UrlUtils.isHttp(it) },
            isLive = type == StreamType.HLS,
            detectedAt = nowMs,
        )
    }

    /** Absolute http(s) URL of a playlist the user typed ("example.com/list.m3u" gets https://), or null. */
    fun playlistUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val url = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return url.takeIf { UrlUtils.isHttp(it) && UrlUtils.hostOf(it) != null }
    }

    /** Display name for a playlist loaded from [url]: its file name, else its host. */
    fun nameFromUrl(url: String): String = UrlUtils.fileNameOf(url) ?: UrlUtils.hostOf(url) ?: url
}

/** Bounded reads, so a huge or endless response cannot exhaust memory. */
internal object BoundedReader {
    /** All bytes of [input], or null when there are more than [maxBytes]. Does not close [input]. */
    fun readAtMost(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private const val BUFFER_SIZE = 16 * 1024
}
