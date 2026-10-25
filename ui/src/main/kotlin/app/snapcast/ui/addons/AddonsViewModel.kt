package app.snapcast.ui.addons

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.addons.AddonInfo
import app.snapcast.addons.AddonInstallState
import app.snapcast.addons.AddonsRepository
import app.snapcast.ui.R
import app.snapcast.ui.shared.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AddonsUiState(
    val addons: List<AddonInfo> = emptyList(),
    val installing: Boolean = false,
    /** Last install failure, kept until the user dismisses it. */
    val installError: String? = null,
) {
    val uBlockInstalled: Boolean get() = addons.any { it.id == AddonsRepository.UBLOCK_ORIGIN_ID }
}

/**
 * Add-ons screen logic. Engine-facing calls go straight to [AddonsRepository] on the main thread; picked
 * .xpi documents are first copied to a cache file because GeckoView installs only https:, file: and
 * resource: URIs (not content: URIs).
 */
@HiltViewModel
internal class AddonsViewModel @Inject constructor(
    private val repository: AddonsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val installError = MutableStateFlow<String?>(null)
    private val copying = MutableStateFlow(false)
    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)

    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<AddonsUiState> =
        combine(repository.addons, repository.installState, installError, copying) { addons, install, error, copy ->
            AddonsUiState(
                addons = addons,
                installing = copy || install is AddonInstallState.Installing,
                installError = error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AddonsUiState())

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

    /** Installs a user-picked .xpi document. */
    fun installFromFile(uri: Uri) {
        if (copying.value) return
        installError.value = null
        copying.value = true
        viewModelScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { copyToCache(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Reading the add-on file failed", e)
                null
            } catch (e: SecurityException) {
                Log.w(TAG, "No access to the add-on file", e)
                null
            } finally {
                copying.value = false
            }
            if (file == null) {
                messageChannel.trySend(UiMessage.res(R.string.ui_screens_addons_file_unreadable))
            } else {
                repository.install(Uri.fromFile(file).toString())
            }
        }
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

    /**
     * Copies [uri] to `cache/addon-install/` (replacing earlier copies) and returns the file, or null when the
     * document is empty, unreadable or larger than [MAX_XPI_BYTES].
     */
    private fun copyToCache(uri: Uri): File? {
        val dir = File(context.cacheDir, INSTALL_DIR)
        dir.listFiles()?.forEach { it.delete() }
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, "addon-${System.currentTimeMillis()}.xpi")
        val input = context.contentResolver.openInputStream(uri) ?: return null
        var total = 0L
        input.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_XPI_BYTES) {
                        sink.close()
                        target.delete()
                        return null
                    }
                    sink.write(buffer, 0, read)
                }
            }
        }
        if (total == 0L) {
            target.delete()
            return null
        }
        return target
    }

    private companion object {
        const val TAG = "SnapcastAddons"
        const val INSTALL_DIR = "addon-install"
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_XPI_BYTES = 100L * 1024 * 1024
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
