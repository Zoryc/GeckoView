package app.snapcast.ui.prompts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapcast.addons.AddonInstallState
import app.snapcast.browser.BrowserPrompt
import app.snapcast.ui.R
import kotlinx.coroutines.launch

/**
 * Dialogs and sheets that can appear over any screen: the oldest pending web prompt of the selected tab
 * (or one not tied to a tab), add-on install permission prompts, install progress snackbars and extension
 * popups.
 */
@Composable
internal fun AppOverlays(modifier: Modifier = Modifier, viewModel: OverlaysViewModel = hiltViewModel()) {
    val prompts by viewModel.prompts.collectAsStateWithLifecycle()
    val selectedTabId by viewModel.selectedTabId.collectAsStateWithLifecycle()
    val addonPrompt by viewModel.addonPermissionPrompt.collectAsStateWithLifecycle()
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val popup by viewModel.popup.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val currentAddonPrompt = addonPrompt
    if (currentAddonPrompt != null) {
        key(currentAddonPrompt.id) { AddonPermissionDialog(currentAddonPrompt) }
    } else {
        PromptHost(prompt = visiblePrompt(prompts, selectedTabId))
    }

    popup?.let { current ->
        key(current.session) { ExtensionPopupSheet(popup = current, onDismiss = viewModel::dismissPopup) }
    }

    InstallStateSnackbars(
        state = installState,
        hostState = snackbarHostState,
        onFinished = viewModel::resetInstallState,
    )

    Box(modifier = modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(bottom = 72.dp),
        )
    }
}

/**
 * The prompt to show: the oldest one of the selected tab, or one not tied to a tab. Only the tab in front
 * may show dialogs, so a background or private tab cannot put a dialog over another site or block the
 * selected tab's own prompts. Other tabs' prompts stay pending (hiding one does not answer it) and appear
 * once their tab is selected; closing a tab dismisses its prompts.
 */
internal fun visiblePrompt(prompts: List<BrowserPrompt>, selectedTabId: String?): BrowserPrompt? =
    prompts.firstOrNull { it.tabId == null || it.tabId == selectedTabId }

/**
 * "Installing…" while an install runs (dismissed automatically when it ends), then "Installed X" or the
 * failure message; the repository state is reset once the result has been shown.
 */
@Composable
private fun InstallStateSnackbars(
    state: AddonInstallState,
    hostState: SnackbarHostState,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) {
        when (state) {
            AddonInstallState.Idle -> Unit
            is AddonInstallState.Installing -> hostState.showSnackbar(
                message = context.getString(R.string.ui_addon_prompt_installing),
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            )
            is AddonInstallState.Installed -> {
                // Launched outside this effect so resetting the state does not cancel the snackbar.
                scope.launch {
                    hostState.showSnackbar(context.getString(R.string.ui_addon_prompt_installed, state.addonName))
                }
                onFinished()
            }
            is AddonInstallState.Failed -> {
                scope.launch {
                    hostState.showSnackbar(
                        message = context.getString(R.string.ui_addon_prompt_failed, state.message),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                }
                onFinished()
            }
        }
    }
}
