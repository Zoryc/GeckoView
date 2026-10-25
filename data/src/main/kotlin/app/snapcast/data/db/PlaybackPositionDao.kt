package app.snapcast.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Resume positions keyed by media URL. */
@Dao
abstract class PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE url = :url")
    abstract suspend fun get(url: String): PlaybackPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(position: PlaybackPositionEntity)

    @Query("DELETE FROM playback_positions WHERE url = :url")
    abstract suspend fun delete(url: String)

    @Query("DELETE FROM playback_positions")
    abstract suspend fun deleteAll()

    /** Keeps only the [keep] most recently updated entries. */
    @Query(
        "DELETE FROM playback_positions WHERE url NOT IN " +
            "(SELECT url FROM playback_positions ORDER BY updatedAt DESC LIMIT :keep)",
    )
    abstract suspend fun trimTo(keep: Int)

    /** Stores [position] and drops the oldest entries beyond [keep]. */
    @Transaction
    open suspend fun putAndTrim(position: PlaybackPositionEntity, keep: Int) {
        put(position)
        trimTo(keep)
    }
}
