package app.snapcast.data.bookmarks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.snapcast.data.db.BookmarkDao
import app.snapcast.data.db.BookmarkEntity
import app.snapcast.data.db.toEntity
import app.snapcast.data.db.toModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room-backed [BookmarkRepository]. URLs are trimmed and blank URLs are never bookmarked ([add] returns -1,
 * [toggle] returns false, [update] is ignored); a blank title falls back to the URL.
 *
 * The default speed dial is inserted once: a DataStore flag records that seeding happened, so defaults the
 * user deletes do not come back.
 */
@Singleton
internal class RoomBookmarkRepository @Inject constructor(
    private val dao: BookmarkDao,
    private val dataStore: DataStore<Preferences>,
) : BookmarkRepository {

    private val seedMutex = Mutex()

    override fun observeBookmarks(): Flow<List<Bookmark>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()

    override fun observeSpeedDial(): Flow<List<Bookmark>> =
        dao.observeOnHome().map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()

    override fun observeIsBookmarked(url: String): Flow<Boolean> =
        dao.observeIdsForUrl(url.trim()).map { it.isNotEmpty() }.distinctUntilChanged()

    override suspend fun add(url: String, title: String, onHome: Boolean): Long {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return NO_ID
        return dao.addOrUpdate(
            url = cleanUrl,
            title = DefaultBookmarks.displayTitle(cleanUrl, title),
            onHome = onHome,
            createdAt = System.currentTimeMillis(),
        )
    }

    override suspend fun update(bookmark: Bookmark) {
        val cleanUrl = bookmark.url.trim()
        if (cleanUrl.isEmpty()) return
        val cleaned = bookmark.copy(url = cleanUrl, title = DefaultBookmarks.displayTitle(cleanUrl, bookmark.title))
        dao.update(cleaned.toEntity())
    }

    override suspend fun remove(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun toggle(url: String, title: String): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return false
        return dao.toggle(
            url = cleanUrl,
            title = DefaultBookmarks.displayTitle(cleanUrl, title),
            createdAt = System.currentTimeMillis(),
        )
    }

    override suspend fun seedDefaultsIfNeeded() {
        seedMutex.withLock {
            if (dataStore.data.first()[DEFAULTS_SEEDED] == true) return
            val now = System.currentTimeMillis()
            val defaults = DefaultBookmarks.ALL.map { site ->
                BookmarkEntity(url = site.url, title = site.title, createdAt = now, position = 0, onHome = true)
            }
            dao.appendMissing(defaults)
            dataStore.edit { it[DEFAULTS_SEEDED] = true }
        }
    }

    private companion object {
        const val NO_ID = -1L

        /** Lives in the settings DataStore file; not part of AppSettings. */
        val DEFAULTS_SEEDED = booleanPreferencesKey("data_default_bookmarks_seeded")
    }
}
