package app.snapcast.data.playback

import app.snapcast.data.db.PlaybackPositionDao
import app.snapcast.data.db.PlaybackPositionEntity
import app.snapcast.data.db.toModel
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed [PlaybackPositionRepository]; storage rules come from [ResumePolicy]. */
@Singleton
internal class RoomPlaybackPositionRepository @Inject constructor(
    private val dao: PlaybackPositionDao,
) : PlaybackPositionRepository {

    override suspend fun get(url: String): PlaybackPosition? = dao.get(url)?.toModel()

    override suspend fun save(url: String, positionMs: Long, durationMs: Long) {
        if (url.isBlank()) return
        if (ResumePolicy.shouldRemember(positionMs, durationMs)) {
            val entity = PlaybackPositionEntity(
                url = url,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
            )
            dao.putAndTrim(entity, MAX_ENTRIES)
        } else {
            dao.delete(url)
        }
    }

    override suspend fun clear(url: String) {
        dao.delete(url)
    }

    override suspend fun clearAll() {
        dao.deleteAll()
    }

    private companion object {
        /** Bounds the table; the least recently updated positions are dropped first. */
        const val MAX_ENTRIES = 500
    }
}
