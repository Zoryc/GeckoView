package app.snapcast.browser.tabs

import android.util.Log
import app.snapcast.data.di.ApplicationScope
import app.snapcast.data.tabs.SavedTab
import app.snapcast.data.tabs.TabSessionStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saves normal tabs through [TabSessionStore] at most once per [SAVE_INTERVAL_MS]. Snapshots are
 * taken on the main thread when the save fires (so the latest state wins) and written by a single
 * IO consumer in order; identical consecutive snapshots are skipped.
 */
@Singleton
internal class TabPersistence @Inject constructor(
    private val store: TabSessionStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val writes = Channel<List<SavedTab>>(Channel.CONFLATED)
    private var pendingSave: Job? = null
    private var lastWritten: List<SavedTab>? = null

    init {
        scope.launch(Dispatchers.IO) {
            for (tabs in writes) {
                if (tabs == lastWritten) continue
                try {
                    store.save(tabs)
                    lastWritten = tabs
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Saving tabs failed", e)
                }
            }
        }
    }

    suspend fun load(): List<SavedTab> = withContext(Dispatchers.IO) {
        try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Restoring tabs failed", e)
            emptyList()
        }
    }

    /** Main thread: saves [snapshot] soon unless a save is already scheduled. */
    fun schedule(snapshot: () -> List<SavedTab>) {
        if (pendingSave?.isActive == true) return
        pendingSave = scope.launch(Dispatchers.Main.immediate) {
            delay(SAVE_INTERVAL_MS)
            writes.trySend(snapshot())
        }
    }

    /** Main thread: saves [tabs] right away (app going to the background). */
    fun saveNow(tabs: List<SavedTab>) {
        pendingSave?.cancel()
        pendingSave = null
        writes.trySend(tabs)
    }

    private companion object {
        const val TAG = "SnapcastBrowser"
        const val SAVE_INTERVAL_MS = 1_000L
    }
}
