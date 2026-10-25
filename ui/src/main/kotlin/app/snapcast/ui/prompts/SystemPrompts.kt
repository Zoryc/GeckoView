package app.snapcast.ui.prompts

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.snapcast.browser.BrowserPrompt
import app.snapcast.ui.R
import app.snapcast.ui.util.shareText

/**
 * `<input type=file>`: launches the system document picker (OpenDocument / OpenMultipleDocuments with
 * the accepted MIME types), falling back to GetContent when no document provider exists, and returns
 * the picked content URIs. The launch happens once even across activity recreation.
 */
@Composable
internal fun FilePickerPrompt(prompt: BrowserPrompt.FilePicker) {
    val once = rememberOnce(prompt.id)
    var launched by rememberSaveable(prompt.id) { mutableStateOf(false) }
    val mimeTypes = remember(prompt.id) {
        prompt.mimeTypes.map { it.trim() }.filter { '/' in it }.distinct().ifEmpty { listOf("*/*") }
    }
    val resultSingle: (Uri?) -> Unit = { uri -> once { prompt.onResult(uri?.let { listOf(it) }) } }
    val resultMultiple: (List<Uri>) -> Unit = { uris -> once { prompt.onResult(uris.ifEmpty { null }) } }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), resultSingle)
    val openDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), resultMultiple)
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), resultSingle)
    val getContents = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents(), resultMultiple)

    LaunchedEffect(prompt.id) {
        if (launched) return@LaunchedEffect
        launched = true
        try {
            if (prompt.multiple) openDocuments.launch(mimeTypes.toTypedArray()) else openDocument.launch(mimeTypes.toTypedArray())
        } catch (e: ActivityNotFoundException) {
            val mime = mimeTypes.singleOrNull() ?: "*/*"
            try {
                if (prompt.multiple) getContents.launch(mime) else getContent.launch(mime)
            } catch (e2: ActivityNotFoundException) {
                once { prompt.onResult(null) }
            }
        }
    }
}

/** navigator.share(): shows the Android share sheet and reports that it was shown. */
@Composable
internal fun SharePrompt(prompt: BrowserPrompt.Share) {
    val context = LocalContext.current
    val once = rememberOnce(prompt.id)
    val chooserTitle = stringResource(R.string.ui_prompt_share_title)
    LaunchedEffect(prompt.id) {
        val text = listOfNotNull(
            prompt.text?.takeIf { it.isNotBlank() },
            prompt.url?.takeIf { it.isNotBlank() && prompt.text?.contains(it) != true },
        ).joinToString("\n")
        val shown = text.isNotBlank() && shareText(context, text, prompt.title, chooserTitle)
        once { prompt.onResult(shown) }
    }
}

/** Runtime permissions the engine needs (e.g. location for a site that was allowed to use it). */
@Composable
internal fun AndroidPermissionsPrompt(prompt: BrowserPrompt.AndroidPermissions) {
    val context = LocalContext.current
    val once = rememberOnce(prompt.id)
    var launched by rememberSaveable(prompt.id) { mutableStateOf(false) }
    val isGranted = { permission: String ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        once { prompt.onResult(prompt.permissions.all { result[it] == true || isGranted(it) }) }
    }
    LaunchedEffect(prompt.id) {
        if (launched) return@LaunchedEffect
        launched = true
        val missing = prompt.permissions.filterNot(isGranted)
        if (missing.isEmpty()) {
            once { prompt.onResult(true) }
        } else {
            launcher.launch(missing.toTypedArray())
        }
    }
}
