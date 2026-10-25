package app.snapcast.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.snapcast.data.bookmarks.Bookmark
import app.snapcast.data.history.HistoryEntry
import app.snapcast.data.playback.PlaybackPosition

/*
 * Room schema types. They are public only because Room's generated DAO implementations reference them;
 * other modules should use the repositories (HistoryRepository, BookmarkRepository, PlaybackPositionRepository).
 */

/** One top-level page visit (consecutive visits to the same URL share a row). */
@Entity(
    tableName = "history",
    indices = [Index(value = ["url"]), Index(value = ["visitedAt"])],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

/** A bookmark; [url] is unique. */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["url"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long,
    val position: Int,
    val onHome: Boolean,
)

/** Last known playback position of a media URL ("resume where you left off"). */
@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    @PrimaryKey val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

internal fun HistoryEntity.toModel(): HistoryEntry = HistoryEntry(id = id, url = url, title = title, visitedAt = visitedAt)

internal fun BookmarkEntity.toModel(): Bookmark = Bookmark(
    id = id,
    url = url,
    title = title,
    createdAt = createdAt,
    position = position,
    onHome = onHome,
)

internal fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    url = url,
    title = title,
    createdAt = createdAt,
    position = position,
    onHome = onHome,
)

internal fun PlaybackPositionEntity.toModel(): PlaybackPosition = PlaybackPosition(
    url = url,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
)
