package app.snapcast.core.detection

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.PagePlayback

/**
 * Which `<video>` of a page its viewer watches, from the page's [PlaybackMessage]s, latest first among:
 * one playing with sound; one paused (not ended) with sound, as when someone pauses the episode before
 * opening the list while a muted ad or preview keeps playing; one playing muted; any other. A playing element
 * whose heartbeat stopped (its frame was removed or navigated without a pause event) no longer counts as
 * playing. One per tab, main thread only.
 */
class PagePlaybackTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private class Player(val message: PlaybackMessage, var seenAt: Long)

    /** Per element, least recently changed first (heartbeats do not move it). */
    private val players = LinkedHashMap<String, Player>()

    /** Records [message]; false when it changed nothing (a heartbeat only keeps its element alive). */
    fun onPlayback(message: PlaybackMessage): Boolean {
        val key = "${message.frame ?: message.frameUrl.orEmpty()}#${message.element}"
        if (message.src.isNullOrBlank() && !message.blob) return players.remove(key) != null
        val now = clock()
        val existing = players[key]
        if (existing != null && existing.message == message) {
            existing.seenAt = now
            return false
        }
        players.remove(key)
        players[key] = Player(message, now)
        if (players.size > MAX_PLAYERS) {
            // Paused and stale elements go first: a long-running episode must outlive ads and previews.
            val evicted = players.entries.firstOrNull { !it.value.isPlaying(now) } ?: players.entries.first()
            players.remove(evicted.key)
        }
        return true
    }

    /** The element being watched, or null when nothing has played yet. */
    val current: PlaybackMessage?
        get() {
            val now = clock()
            val all = players.values
            return (
                all.lastOrNull { it.isPlaying(now) && !it.message.muted }
                    ?: all.lastOrNull { it.message.paused && !it.message.ended && !it.message.muted }
                    ?: all.lastOrNull { it.isPlaying(now) }
                    ?: all.lastOrNull { it.message.paused }
                )?.message
        }

    fun clear() = players.clear()

    private fun Player.isPlaying(now: Long): Boolean = !message.paused && now - seenAt <= STALE_MS

    companion object {
        private const val MAX_PLAYERS = 20

        /** A playing element not heard from for this long is gone (heartbeats come every 10 s). */
        const val STALE_MS = 35_000L

        /** [videos] with [playingId] first and marked with [state]; the others keep their order. */
        fun mark(videos: List<DetectedVideo>, playingId: String, state: PagePlayback): List<DetectedVideo> {
            val index = videos.indexOfFirst { it.id == playingId }
            if (index < 0) return videos
            return buildList(videos.size) {
                add(videos[index].copy(pagePlayback = state))
                for (i in videos.indices) if (i != index) add(videos[i])
            }
        }
    }
}
