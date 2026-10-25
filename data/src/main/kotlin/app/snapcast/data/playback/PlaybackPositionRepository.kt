package app.snapcast.data.playback

data class PlaybackPosition(
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** "Resume where you left off" storage for cast and local playback, keyed by the original media URL. */
interface PlaybackPositionRepository {
    suspend fun get(url: String): PlaybackPosition?

    /** Positions in the first 10 s or the last 30 s (or >= 95 %) of the media clear the entry instead. */
    suspend fun save(url: String, positionMs: Long, durationMs: Long)

    suspend fun clear(url: String)

    suspend fun clearAll()
}
