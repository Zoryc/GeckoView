package app.snapcast.data.tabs

import android.content.Context
import android.util.Log
import app.snapcast.data.util.AtomicFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [TabSessionStore] that keeps the tab list as JSON in `filesDir/tabs.json`, replaced atomically on every save.
 * A missing, unreadable or corrupt file loads as an empty list; failed saves are logged (the previous file stays).
 */
@Singleton
internal class FileTabSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : TabSessionStore {

    private val mutex = Mutex()

    override suspend fun load(): List<SavedTab> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = file()
            if (!file.isFile) {
                emptyList<SavedTab>()
            } else {
                try {
                    SavedTabsCodec.decode(file.readText(Charsets.UTF_8))
                } catch (e: IOException) {
                    Log.w(TAG, "Could not read saved tabs", e)
                    emptyList<SavedTab>()
                }
            }
        }
    }

    override suspend fun save(tabs: List<SavedTab>) {
        val text = SavedTabsCodec.encode(tabs)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    AtomicFiles.writeText(file(), text)
                } catch (e: IOException) {
                    Log.w(TAG, "Could not save tabs", e)
                }
            }
        }
    }

    private fun file(): File = File(context.filesDir, FILE_NAME)

    private companion object {
        const val TAG = "SnapcastData"
        const val FILE_NAME = "tabs.json"
    }
}
