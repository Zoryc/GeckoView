package app.snapcast.data.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import app.snapcast.core.model.AppSettings
import app.snapcast.data.di.ApplicationScope
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn

/**
 * [SettingsRepository] backed by the "settings" Preferences DataStore; the mapping lives in [SettingsMapper].
 * Read errors are retried a few times and then fall back to defaults; write errors are logged and dropped so
 * a settings toggle never crashes the app.
 */
@Singleton
internal class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope scope: CoroutineScope,
) : SettingsRepository {

    override val settings: StateFlow<AppSettings> = dataStore.data
        .retryWhen { cause, attempt ->
            val retry = cause is IOException && attempt < READ_RETRIES
            if (retry) delay(RETRY_DELAY_MS * (attempt + 1))
            retry
        }
        .catch { cause ->
            if (cause !is IOException) throw cause
            Log.w(TAG, "Could not read settings; using defaults", cause)
            emit(emptyPreferences())
        }
        .map { SettingsMapper.read(it) }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            dataStore.edit { prefs ->
                val current = SettingsMapper.read(prefs)
                SettingsMapper.write(prefs, SettingsMapper.sanitize(transform(current), fallback = current))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not save settings", e)
        }
    }

    private companion object {
        const val TAG = "SnapcastData"
        const val READ_RETRIES = 3L
        const val RETRY_DELAY_MS = 250L
    }
}
