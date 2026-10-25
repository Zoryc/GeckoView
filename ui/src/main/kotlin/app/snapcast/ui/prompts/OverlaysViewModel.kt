package app.snapcast.ui.prompts

import androidx.lifecycle.ViewModel
import app.snapcast.addons.AddonInstallState
import app.snapcast.addons.AddonPermissionPrompt
import app.snapcast.addons.AddonsRepository
import app.snapcast.addons.ExtensionPopup
import app.snapcast.browser.BrowserPrompt
import app.snapcast.browser.BrowserTabs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** App-wide dialogs that may appear on any screen: web prompts and add-on install/permission/popup UI. */
@HiltViewModel
internal class OverlaysViewModel @Inject constructor(
    browserTabs: BrowserTabs,
    private val addonsRepository: AddonsRepository,
) : ViewModel() {
    val prompts: StateFlow<List<BrowserPrompt>> = browserTabs.prompts

    val addonPermissionPrompt: StateFlow<AddonPermissionPrompt?> = addonsRepository.permissionPrompt

    val installState: StateFlow<AddonInstallState> = addonsRepository.installState

    val popup: StateFlow<ExtensionPopup?> = addonsRepository.popup

    fun resetInstallState() = addonsRepository.resetInstallState()

    fun dismissPopup() = addonsRepository.dismissPopup()
}
