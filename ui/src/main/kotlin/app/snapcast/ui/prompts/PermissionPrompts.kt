package app.snapcast.ui.prompts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.snapcast.browser.BrowserPrompt
import app.snapcast.browser.BrowserPrompt.PermissionKind
import app.snapcast.core.util.UrlUtils
import app.snapcast.ui.R

private fun PermissionKind.icon(): ImageVector = when (this) {
    PermissionKind.GEOLOCATION -> Icons.Filled.LocationOn
    PermissionKind.NOTIFICATIONS -> Icons.Filled.Notifications
    PermissionKind.PERSISTENT_STORAGE -> Icons.Filled.Storage
    PermissionKind.XR -> Icons.Filled.ViewInAr
    PermissionKind.AUTOPLAY -> Icons.Filled.PlayCircle
    PermissionKind.MEDIA_KEY_SYSTEM -> Icons.Filled.Lock
    PermissionKind.STORAGE_ACCESS -> Icons.Filled.Cookie
    PermissionKind.LOCAL_NETWORK -> Icons.Filled.Lan
    PermissionKind.OTHER -> Icons.Filled.Security
}

private fun PermissionKind.messageRes(): Int = when (this) {
    PermissionKind.GEOLOCATION -> R.string.ui_prompt_perm_location
    PermissionKind.NOTIFICATIONS -> R.string.ui_prompt_perm_notifications
    PermissionKind.PERSISTENT_STORAGE -> R.string.ui_prompt_perm_storage
    PermissionKind.XR -> R.string.ui_prompt_perm_xr
    PermissionKind.AUTOPLAY -> R.string.ui_prompt_perm_autoplay
    PermissionKind.MEDIA_KEY_SYSTEM -> R.string.ui_prompt_perm_drm
    PermissionKind.STORAGE_ACCESS -> R.string.ui_prompt_perm_storage_access
    PermissionKind.LOCAL_NETWORK -> R.string.ui_prompt_perm_local_network
    PermissionKind.OTHER -> R.string.ui_prompt_perm_other
}

/** Origin shown in permission prompts: the host when it parses, else the raw origin. */
private fun displayOrigin(origin: String): String = UrlUtils.hostOf(origin) ?: origin

/** Site permission request with kind-specific wording. */
@Composable
internal fun ContentPermissionPrompt(prompt: BrowserPrompt.ContentPermission) {
    val once = rememberOnce(prompt.id)
    AlertDialog(
        // Back or a tap outside is not a choice: deny this time only, so the site may ask again. Only Block
        // is remembered for the site.
        onDismissRequest = { once { prompt.dismiss() } },
        icon = { Icon(prompt.kind.icon(), contentDescription = null) },
        title = { Text(stringResource(R.string.ui_prompt_perm_title)) },
        text = { Text(stringResource(prompt.kind.messageRes(), displayOrigin(prompt.origin))) },
        confirmButton = {
            TextButton(onClick = { once { prompt.onResult(true) } }) { Text(stringResource(R.string.ui_prompt_allow)) }
        },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(false) } }) { Text(stringResource(R.string.ui_prompt_block)) }
        },
    )
}

/** getUserMedia(): pick the camera and/or microphone to share, or block. */
@Composable
internal fun MediaPermissionPrompt(prompt: BrowserPrompt.MediaPermission) {
    val once = rememberOnce(prompt.id)
    var videoId by rememberSaveable(prompt.id) { mutableStateOf(prompt.videoSources.firstOrNull()?.id) }
    var audioId by rememberSaveable(prompt.id) { mutableStateOf(prompt.audioSources.firstOrNull()?.id) }
    val messageRes = when {
        prompt.videoSources.isNotEmpty() && prompt.audioSources.isNotEmpty() -> R.string.ui_prompt_media_both
        prompt.videoSources.isNotEmpty() -> R.string.ui_prompt_media_camera
        else -> R.string.ui_prompt_media_mic
    }
    AlertDialog(
        onDismissRequest = { once { prompt.onResult(null, null) } },
        icon = {
            Icon(
                imageVector = if (prompt.videoSources.isNotEmpty()) Icons.Filled.Videocam else Icons.Filled.Mic,
                contentDescription = null,
            )
        },
        title = { Text(stringResource(R.string.ui_prompt_perm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(messageRes, displayOrigin(prompt.origin)))
                if (prompt.videoSources.size > 1) {
                    SourcePicker(
                        label = stringResource(R.string.ui_prompt_media_camera_label),
                        sources = prompt.videoSources,
                        selectedId = videoId,
                        onSelect = { videoId = it },
                    )
                }
                if (prompt.audioSources.size > 1) {
                    SourcePicker(
                        label = stringResource(R.string.ui_prompt_media_mic_label),
                        sources = prompt.audioSources,
                        selectedId = audioId,
                        onSelect = { audioId = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { once { prompt.onResult(videoId, audioId) } }) {
                Text(stringResource(R.string.ui_prompt_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(null, null) } }) { Text(stringResource(R.string.ui_prompt_block)) }
        },
    )
}

@Composable
private fun SourcePicker(
    label: String,
    sources: List<BrowserPrompt.MediaSourceInfo>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        sources.forEach { source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = source.id == selectedId, role = Role.RadioButton) { onSelect(source.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = source.id == selectedId, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(source.name)
            }
        }
    }
}
