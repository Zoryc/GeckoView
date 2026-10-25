package app.snapcast.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.browser.BrowserTabs
import app.snapcast.core.model.AppSettings
import app.snapcast.core.model.SearchEngine
import app.snapcast.core.model.ThemeMode
import app.snapcast.data.history.HistoryRepository
import app.snapcast.data.playback.PlaybackPositionRepository
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.ui.R
import app.snapcast.ui.shared.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Data the user can wipe from the Privacy section. */
internal enum class ClearAction { COOKIES, CACHE, SITE_DATA, HISTORY }

/** Settings screen logic. Every change is written through [SettingsRepository.update]. */
@HiltViewModel
internal class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val browserTabs: BrowserTabs,
    private val history: HistoryRepository,
    private val playbackPositions: PlaybackPositionRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    private val clearing = MutableStateFlow<Set<ClearAction>>(emptySet())

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    /** Clear actions currently running (their rows are disabled). */
    val clearInProgress: StateFlow<Set<ClearAction>> = clearing.asStateFlow()

    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    val about: AboutInfo = AboutInfo.read(context)

    // General

    fun setSearchEngine(engine: SearchEngine) = update { it.copy(searchEngine = engine) }

    fun useBuiltInHomePage() = update { it.copy(homePage = AppSettings.HOME_PAGE_BUILT_IN) }

    /** Stores [url] as the home page; returns false (and changes nothing) when it is not a valid web address. */
    fun setCustomHomePage(url: String): Boolean {
        val normalized = HomePageInput.normalize(url) ?: return false
        update { it.copy(homePage = normalized) }
        return true
    }

    fun setDesktopModeDefault(enabled: Boolean) = update { it.copy(desktopModeDefault = enabled) }

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    // Video detection

    fun setMinVideoSize(bytes: Long) = update { it.copy(minVideoSizeBytes = bytes.coerceAtLeast(0)) }

    fun setMinVideoDuration(seconds: Int) = update { it.copy(minVideoDurationSec = seconds.coerceAtLeast(0)) }

    /** Validates [input] against the current list and adds it when valid; the result tells the editor what happened. */
    fun addIgnoredDomain(input: String): DomainCheck {
        val check = DomainValidation.check(input, settings.value.ignoredDomains)
        if (check is DomainCheck.Valid) update { it.copy(ignoredDomains = it.ignoredDomains + check.domain) }
        return check
    }

    fun removeIgnoredDomain(domain: String) = update { it.copy(ignoredDomains = it.ignoredDomains - domain) }

    fun restoreDefaultIgnoredDomains() = update { it.copy(ignoredDomains = AppSettings.DEFAULT_IGNORED_DOMAINS) }

    // Casting

    fun setProxyEnabled(enabled: Boolean) = update { it.copy(proxyEnabled = enabled) }

    fun setProxyPort(port: Int) {
        if (port !in PortValidation.MIN_PORT..PortValidation.MAX_PORT) return
        update { it.copy(proxyPort = port) }
    }

    fun setResumePlayback(enabled: Boolean) = update { it.copy(resumePlayback = enabled) }

    // Privacy

    fun clear(action: ClearAction) {
        if (action in clearing.value) return
        clearing.update { it + action }
        viewModelScope.launch {
            try {
                when (action) {
                    ClearAction.COOKIES -> browserTabs.clearBrowsingData(BrowserTabs.CLEAR_COOKIES)
                    ClearAction.CACHE -> browserTabs.clearBrowsingData(BrowserTabs.CLEAR_CACHE)
                    ClearAction.SITE_DATA -> browserTabs.clearBrowsingData(BrowserTabs.CLEAR_SITE_DATA)
                    ClearAction.HISTORY -> {
                        history.clear()
                        playbackPositions.clearAll()
                    }
                }
                messageChannel.trySend(UiMessage.res(doneMessage(action)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Clearing $action failed", e)
                messageChannel.trySend(UiMessage.res(R.string.ui_screens_settings_clear_failed))
            } finally {
                clearing.update { it - action }
            }
        }
    }

    private fun doneMessage(action: ClearAction): Int = when (action) {
        ClearAction.COOKIES -> R.string.ui_screens_settings_cookies_cleared
        ClearAction.CACHE -> R.string.ui_screens_settings_cache_cleared
        ClearAction.SITE_DATA -> R.string.ui_screens_settings_site_data_cleared
        ClearAction.HISTORY -> R.string.ui_screens_settings_history_cleared
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                settingsRepository.update(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving settings failed", e)
                messageChannel.trySend(UiMessage.res(R.string.ui_screens_settings_save_failed))
            }
        }
    }

    private companion object {
        const val TAG = "SnapcastSettings"
    }
}
