package app.snapcast.data.settings

import app.snapcast.core.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    /** Current settings; starts with defaults and is updated once DataStore has been read. */
    val settings: StateFlow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}
