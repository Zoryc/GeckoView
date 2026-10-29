package app.snapcast.core.detection

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.PagePlayback

/**
 * Which `<video>` of a page its viewer watches, from the page's [PlaybackMessage]s: the one that started
 * playing last (an unmuted one before muted ones: a muted autoplay preview is rarely what someone watches),
 * else the one paused last. One per tab, main thread only.
 */
class PagePlaybackTracker {
    /** Latest message per element, least recently changed first. */
    private val players = LinkedHashMap<String, PlaybackMessage>()

    /** Records [message]; false when it changed nothing. */
    fun onPlayback(message: PlaybackMessage): Boolean {
        val key = "${message.frameUrl.orEmpty()}#${message.element}"
        if (message.src.isNullOrBlank() && !message.blob) return players.remove(key) != null
        if (players[key] == message) return false
        players.remove(key)
        players[key] = message
        while (players.size > MAX_PLAYERS) players.remove(players.keys.first())
        return true
    }

    /** The element being watched, or null when nothing has played yet. */
    val current: PlaybackMessage?
        get() {
            val playing = players.values.filter { !it.paused }
            if (playing.isEmpty()) return players.values.lastOrNull()
            return playing.lastOrNull { !it.muted } ?: playing.last()
        }

    fun clear() = players.clear()

    companion object {
        private const val MAX_PLAYERS = 20

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
