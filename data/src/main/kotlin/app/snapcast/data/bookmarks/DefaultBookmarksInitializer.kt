package app.snapcast.data.bookmarks

import android.database.SQLException
import android.util.Log
import app.snapcast.data.AppInitializer
import app.snapcast.data.di.ApplicationScope
import dagger.Lazy
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Seeds the default speed-dial bookmarks in the background at startup. The repository is injected lazily so
 * nothing is opened unless the initializer actually runs (it only runs in the main process).
 */
internal class DefaultBookmarksInitializer @Inject constructor(
    private val bookmarks: Lazy<BookmarkRepository>,
    @ApplicationScope private val scope: CoroutineScope,
) : AppInitializer {

    override fun initialize() {
        scope.launch {
            try {
                bookmarks.get().seedDefaultsIfNeeded()
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the bookmark seeding flag", e)
            } catch (e: SQLException) {
                Log.w(TAG, "Could not insert default bookmarks", e)
            }
        }
    }

    private companion object {
        const val TAG = "SnapcastData"
    }
}
