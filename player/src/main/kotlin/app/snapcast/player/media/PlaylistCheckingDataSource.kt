package app.snapcast.player.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.snapcast.core.media.HlsPlaylistText

/**
 * A playlist link answered with something else (an expired link's web page, a JSON error, nothing). It is a
 * [ParserException] so Media3 fails at once instead of fetching the same expired link again three times.
 */
@OptIn(UnstableApi::class)
internal class NotAPlaylistException(val instead: HlsPlaylistText.Instead, val host: String?) :
    ParserException("Not an HLS playlist but ${instead.description}", null, true, C.DATA_TYPE_MANIFEST)

/**
 * Hands Media3 HLS playlists the way browsers read them. A whole answer is looked at before Media3 reads it:
 * when it is a playlist with byte order marks or blank lines before `#EXTM3U` (which Media3 refuses as
 * malformed), they are skipped; when a playlist was expected ([expectsPlaylist], or a playlist Content-Type)
 * and the answer is a web page, a JSON message or empty, it fails with [NotAPlaylistException], which says what
 * the site sent. Anything else (a key or segment served with a playlist's type, say), ranged requests, segments
 * and files pass through untouched.
 */
@OptIn(UnstableApi::class)
internal class PlaylistCheckingDataSource(
    private val upstream: DataSource,
    private val expectsPlaylist: (Uri) -> Boolean,
) : DataSource {
    private var head = EMPTY
    private var headPosition = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        head = EMPTY
        headPosition = 0
        val length = upstream.open(dataSpec)
        if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) return length
        val start = readStart()
        val offset = HlsPlaylistText.headerOffset(start)
        if (offset < 0 && (expectsPlaylist(dataSpec.uri) || hasPlaylistType())) {
            val instead = HlsPlaylistText.whatInstead(String(start, Charsets.UTF_8))
            if (instead != HlsPlaylistText.Instead.OTHER) throw NotAPlaylistException(instead, dataSpec.uri.host)
        }
        head = start
        headPosition = offset.coerceAtLeast(0)
        return if (length == C.LENGTH_UNSET.toLong()) length else length - headPosition
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (headPosition < head.size) {
            val count = minOf(length, head.size - headPosition)
            System.arraycopy(head, headPosition, buffer, offset, count)
            headPosition += count
            return count
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        head = EMPTY
        headPosition = 0
        upstream.close()
    }

    /** The first bytes of the answer (fewer when it is shorter). */
    private fun readStart(): ByteArray {
        val buffer = ByteArray(PEEK_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val read = upstream.read(buffer, filled, buffer.size - filled)
            if (read == C.RESULT_END_OF_INPUT) break
            filled += read
        }
        return if (filled == buffer.size) buffer else buffer.copyOf(filled)
    }

    private fun hasPlaylistType(): Boolean = upstream.responseHeaders.entries
        .filter { it.key.equals("Content-Type", ignoreCase = true) }
        .flatMap { it.value }
        .any { type -> type.substringBefore(';').trim().lowercase() in PLAYLIST_TYPES }

    class Factory(
        private val upstream: DataSource.Factory,
        private val expectsPlaylist: (Uri) -> Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = PlaylistCheckingDataSource(upstream.createDataSource(), expectsPlaylist)
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val PEEK_BYTES = 1024
        val PLAYLIST_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
    }
}
