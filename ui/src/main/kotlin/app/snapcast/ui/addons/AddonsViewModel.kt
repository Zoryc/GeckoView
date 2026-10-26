package app.snapcast.ui.addons

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.addons.AddonInfo
import app.snapcast.addons.AddonInstallState
import app.snapcast.addons.AddonsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class AddonsUiState(
    val addons: List<AddonInfo> = emptyList(),
    val installing: Boolean = false,
    /** Last install failure, kept until the user dismisses it. */
    val installError: String? = null,
) {
    val uBlockInstalled: Boolean get() = addons.any { it.id == AddonsRepository.UBLOCK_ORIGIN_ID }
}

/**
 * Add-ons screen logic. Engine-facing calls go straight to [AddonsRepository] on the main thread. Picked
 * .xpi documents are handed over as content:// URIs: the repository copies them for GeckoView (which
 * installs only https:, file: and resource: URIs) and deletes the copy once the install ends.
 */
@HiltViewModel
internal class AddonsViewModel @Inject constructor(
    private val repository: AddonsRepository,
) : ViewModel() {

    private val installError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AddonsUiState> =
        combine(repository.addons, repository.installState, installError, ::addonsState).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = addonsState(repository.addons.value, repository.installState.value, installError.value),
        )

    init {
        repository.refresh()
        // The app-wide overlay resets the repository state right after showing it, so failures are kept here.
        viewModelScope.launch {
            repository.installState.collect { state ->
                when (state) {
                    is AddonInstallState.Failed -> installError.value = state.message
                    is AddonInstallState.Installing, is AddonInstallState.Installed -> installError.value = null
                    AddonInstallState.Idle -> Unit
                }
            }
        }
    }

    fun installUBlockOrigin() {
        installError.value = null
        repository.installUBlockOrigin()
    }

    /**
     * Installs a user-picked .xpi document (content:// URI from the document picker). The repository reads
     * it right away, while the picker's read grant is valid, and reports progress and failures (an unreadable
     * file too) through its install state.
     */
    fun installFromFile(uri: Uri) {
        installError.value = null
        repository.install(uri.toString())
    }

    fun dismissInstallError() {
        installError.value = null
        repository.resetInstallState()
    }

    fun setEnabled(id: String, enabled: Boolean) = repository.setEnabled(id, enabled)

    fun setAllowedInPrivateBrowsing(id: String, allowed: Boolean) = repository.setAllowedInPrivateBrowsing(id, allowed)

    fun openOptionsPage(id: String) = repository.openOptionsPage(id)

    /** Opens the add-on's toolbar popup (or fires its click action) for the selected tab. */
    fun openPopup(id: String) = repository.clickAction(id, null)

    fun uninstall(id: String) = repository.uninstall(id)

    private fun addonsState(
        addons: List<AddonInfo>,
        install: AddonInstallState,
        error: String?,
    ) = AddonsUiState(
        addons = addons,
        installing = install is AddonInstallState.Installing,
        installError = error,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
