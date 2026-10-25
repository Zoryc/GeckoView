package app.snapcast.ui.prompts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snapcast.addons.AddonPermissionPrompt
import app.snapcast.addons.ExtensionPopup
import app.snapcast.ui.R
import app.snapcast.ui.components.ExtensionIcon
import app.snapcast.ui.components.GeckoViewHost

/** Install / permission-update confirmation for an add-on. */
@Composable
internal fun AddonPermissionDialog(prompt: AddonPermissionPrompt) {
    val once = rememberOnce(prompt.id)
    var allowInPrivate by remember(prompt.id) { mutableStateOf(false) }
    val entries = prompt.permissions + prompt.origins
    AlertDialog(
        onDismissRequest = { once { prompt.onResult(false, false) } },
        icon = { ExtensionIcon(icon = prompt.icon, size = 40.dp) },
        title = {
            Text(
                text = if (prompt.offerPrivateBrowsing) {
                    stringResource(R.string.ui_addon_prompt_add_title, prompt.addonName)
                } else {
                    stringResource(R.string.ui_addon_prompt_update_title, prompt.addonName)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.ui_addon_prompt_no_permissions))
                } else {
                    Text(stringResource(R.string.ui_addon_prompt_permissions_intro))
                    entries.forEach { entry ->
                        Row {
                            Text("•", modifier = Modifier.width(16.dp))
                            Text(entry, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (prompt.offerPrivateBrowsing) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = allowInPrivate,
                                role = Role.Checkbox,
                                onValueChange = { allowInPrivate = it },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = allowInPrivate, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ui_addon_prompt_allow_private))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { once { prompt.onResult(true, prompt.offerPrivateBrowsing && allowInPrivate) } }) {
                Text(
                    stringResource(
                        if (prompt.offerPrivateBrowsing) R.string.ui_addon_prompt_add else R.string.ui_prompt_allow,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(false, false) } }) {
                Text(stringResource(R.string.ui_prompt_cancel))
            }
        },
    )
}

/** An extension's browser-action popup (e.g. uBlock Origin's panel) rendered in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionPopupSheet(popup: ExtensionPopup, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = popup.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ui_addon_prompt_close_popup))
                }
            }
            GeckoViewHost(
                session = popup.session,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp),
            )
        }
    }
}
