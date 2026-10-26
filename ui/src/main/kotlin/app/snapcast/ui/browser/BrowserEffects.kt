package app.snapcast.ui.browser

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.snapcast.browser.BrowserEvent
import app.snapcast.cast.CastEvent
import app.snapcast.cast.CastResult
import app.snapcast.ui.R
import app.snapcast.ui.shared.CastPermissions
import app.snapcast.ui.util.ExternalOpenResult
import app.snapcast.ui.util.findFragmentActivity
import app.snapcast.ui.util.openDownloadedFile
import app.snapcast.ui.util.openExternalUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs an action that starts casting. Missing permissions are requested first: local network access on
 * Android 17+ (the receiver must reach the phone's proxy) every time it is missing, and on Android 13+ the
 * notification permission (needed for the casting notification and lock-screen controls) once per process.
 * The action continues whatever the answer.
 */
@Composable
internal fun rememberCastPermissionGate(viewModel: BrowserViewModel): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending
        pending = null
        action?.invoke()
    }
    return remember(viewModel, launcher, context) {
        { action: () -> Unit ->
            val missing = CastPermissions.missing(context, askNotifications = !viewModel.notificationPermissionRequested)
            if (missing.isEmpty()) {
                action()
            } else {
                if (Manifest.permission.POST_NOTIFICATIONS in missing) viewModel.notificationPermissionRequested = true
                pending = action
                launcher.launch(missing.toTypedArray())
            }
        }
    }
}

/** Screen-level reactions the event handler can trigger. */
internal class BrowserEventCallbacks(
    val onCastError: (CastResult.Error) -> Unit,
    val onLoadUrl: (String) -> Unit,
    val onReloadTab: (String) -> Unit,
    val onSelectTab: (String) -> Unit,
)

/**
 * Collects [BrowserViewModel.uiEvents] while the screen is started and turns them into snackbars,
 * dialogs and activity launches (external links, opening downloads, the Cast device chooser).
 */
@Composable
internal fun BrowserEventsEffect(
    viewModel: BrowserViewModel,
    snackbarHostState: SnackbarHostState,
    callbacks: BrowserEventCallbacks,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentCallbacks by rememberUpdatedState(callbacks)
    val filter = remember { DuplicateMessageFilter() }
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiEvents.collect { event ->
                val snackbars = Snackbars(context, snackbarHostState, scope, filter)
                handleEvent(event, context, viewModel, snackbars, currentCallbacks)
            }
        }
    }
}

/** Shows snackbars without blocking event collection; drops exact repeats within a short window. */
private class Snackbars(
    private val context: Context,
    private val host: SnackbarHostState,
    private val scope: CoroutineScope,
    private val filter: DuplicateMessageFilter,
) {
    fun show(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
        onAction: () -> Unit = {},
    ) {
        if (!filter.shouldShow(message, System.currentTimeMillis())) return
        scope.launch {
            val result = host.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = actionLabel == null && duration != SnackbarDuration.Short,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    fun showRes(resId: Int, vararg args: Any) {
        show(message = context.getString(resId, *args))
    }
}

private fun handleEvent(
    event: BrowserUiEvent,
    context: Context,
    viewModel: BrowserViewModel,
    snackbars: Snackbars,
    callbacks: BrowserEventCallbacks,
) {
    when (event) {
        is BrowserUiEvent.Browser -> handleBrowserEvent(event.event, context, snackbars, callbacks)
        is BrowserUiEvent.Cast -> when (val cast = event.event) {
            is CastEvent.Started -> snackbars.showRes(R.string.ui_event_casting, cast.title)
            is CastEvent.Queued -> snackbars.showRes(R.string.ui_event_queued, cast.title)
            is CastEvent.Error -> callbacks.onCastError(CastResult.Error(cast.kind, cast.message))
            CastEvent.SessionEnded -> snackbars.showRes(R.string.ui_event_cast_ended)
        }
        is BrowserUiEvent.CastOutcome -> when (val result = event.result) {
            CastResult.Success -> if (event.queued) snackbars.showRes(R.string.ui_event_queued, event.video.displayTitle)
            CastResult.NeedsDevice -> {
                val activity = context.findFragmentActivity()
                if (activity != null) {
                    snackbars.showRes(R.string.ui_event_choose_device)
                    viewModel.showDeviceChooser(activity)
                } else {
                    snackbars.showRes(R.string.ui_event_no_device)
                }
            }
            is CastResult.Error -> callbacks.onCastError(result)
        }
        is BrowserUiEvent.BookmarkToggled -> snackbars.showRes(
            if (event.bookmarked) R.string.ui_event_bookmark_added else R.string.ui_event_bookmark_removed,
        )
        is BrowserUiEvent.OpenedInBackground -> snackbars.show(
            message = context.getString(
                if (event.isPrivate) R.string.ui_event_opened_private_tab else R.string.ui_event_opened_tab,
            ),
            actionLabel = context.getString(R.string.ui_event_switch),
            onAction = { callbacks.onSelectTab(event.tabId) },
        )
    }
}

private fun handleBrowserEvent(
    event: BrowserEvent,
    context: Context,
    snackbars: Snackbars,
    callbacks: BrowserEventCallbacks,
) {
    when (event) {
        is BrowserEvent.Message -> snackbars.show(event.text)
        is BrowserEvent.DownloadStarted -> snackbars.showRes(R.string.ui_event_download_started, event.fileName)
        is BrowserEvent.DownloadFinished -> {
            val uri = event.contentUri
            if (uri != null) {
                snackbars.show(
                    message = context.getString(R.string.ui_event_download_finished, event.fileName),
                    actionLabel = context.getString(R.string.ui_event_open),
                    duration = SnackbarDuration.Long,
                    onAction = {
                        if (!openDownloadedFile(context, uri)) snackbars.showRes(R.string.ui_event_no_app_for_file)
                    },
                )
            } else {
                snackbars.showRes(R.string.ui_event_download_finished, event.fileName)
            }
        }
        is BrowserEvent.DownloadFailed -> snackbars.show(
            message = context.getString(R.string.ui_event_download_failed, event.fileName, event.reason),
            duration = SnackbarDuration.Long,
        )
        is BrowserEvent.OpenExternal -> when (val result = openExternalUri(context, event.uri)) {
            ExternalOpenResult.Launched -> Unit
            is ExternalOpenResult.Fallback -> callbacks.onLoadUrl(result.url)
            ExternalOpenResult.NotHandled -> snackbars.showRes(R.string.ui_event_no_app_for_link)
        }
        is BrowserEvent.TabCrashed -> snackbars.show(
            message = context.getString(R.string.ui_event_tab_crashed),
            actionLabel = context.getString(R.string.ui_event_reload),
            duration = SnackbarDuration.Long,
            onAction = { callbacks.onReloadTab(event.tabId) },
        )
        // Context menus are turned into sheet state by the view model and never reach the screen.
        is BrowserEvent.ContextMenu -> Unit
    }
}
