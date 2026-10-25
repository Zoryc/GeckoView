package app.snapcast.addons

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small persistent add-on bookkeeping:
 * - ids of extensions known to declare a browser/page action, so [AddonInfo.hasBrowserAction] is also
 *   right for disabled add-ons (GeckoView only reports actions of running extensions);
 * - when add-on updates were last checked.
 *
 * [load] must run once on a background thread before anything is written; changes made earlier are
 * kept in memory and merged.
 */
@Singleton
internal class AddonsPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }
    private val lock = Any()
    private var loaded = false
    private val removedBeforeLoad = HashSet<String>()

    private val _knownActionIds = MutableStateFlow<Set<String>>(emptySet())
    val knownActionIds: StateFlow<Set<String>> = _knownActionIds.asStateFlow()

    /** Reads the stored values (blocking disk read). */
    fun load() {
        val stored = prefs.getStringSet(KEY_ACTION_IDS, null)?.toSet().orEmpty()
        synchronized(lock) {
            if (loaded) return
            val merged = _knownActionIds.value + (stored - removedBeforeLoad)
            removedBeforeLoad.clear()
            loaded = true
            _knownActionIds.value = merged
            if (merged != stored) persist(merged)
        }
    }

    fun addKnownAction(extensionId: String) {
        synchronized(lock) {
            val current = _knownActionIds.value
            if (extensionId in current) return
            val next = current + extensionId
            _knownActionIds.value = next
            if (loaded) persist(next)
        }
    }

    fun removeKnownAction(extensionId: String) {
        synchronized(lock) {
            if (!loaded) removedBeforeLoad.add(extensionId)
            val current = _knownActionIds.value
            if (extensionId !in current) return
            val next = current - extensionId
            _knownActionIds.value = next
            if (loaded) persist(next)
        }
    }

    /** Blocking disk read; 0 when never checked. */
    fun lastUpdateCheck(): Long = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun setLastUpdateCheck(timeMillis: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, timeMillis).apply()
    }

    private fun persist(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_ACTION_IDS, HashSet(ids)).apply()
    }

    private companion object {
        const val FILE_NAME = "addons"
        const val KEY_ACTION_IDS = "extensions_with_actions"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}
