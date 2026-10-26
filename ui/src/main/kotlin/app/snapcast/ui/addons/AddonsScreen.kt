package app.snapcast.ui.addons

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapcast.addons.AddonInfo
import app.snapcast.ui.R
import app.snapcast.ui.shared.ConfirmDialog
import app.snapcast.ui.shared.EmptyState
import app.snapcast.ui.shared.ScreenScaffold
import app.snapcast.ui.shared.SectionHeader
import kotlinx.coroutines.launch

/** addons.mozilla.org listing for Firefox for Android. */
private const val AMO_ANDROID_URL = "https://addons.mozilla.org/android/"

private val XPI_MIME_TYPES = arrayOf("application/x-xpinstall", "application/zip", "*/*")

/** Installed add-ons with enable/disable, private-window access, options, popup and removal; installs from AMO or a file. */
@Composable
fun AddonsScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val viewModel: AddonsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.installFromFile(uri)
    }
    val installFromFile: () -> Unit = {
        try {
            pickFile.launch(XPI_MIME_TYPES)
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_screens_no_file_picker)) }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.ui_screens_addons_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (state.installing) {
                item(key = "installing") { InstallingBanner() }
            }
            state.installError?.let { error ->
                item(key = "install-error") { InstallErrorCard(message = error, onDismiss = viewModel::dismissInstallError) }
            }
            if (!state.uBlockInstalled) {
                item(key = "ublock") {
                    UBlockCard(installing = state.installing, onInstall = viewModel::installUBlockOrigin)
                }
            }
            item(key = "sources") {
                InstallSourcesCard(
                    installing = state.installing,
                    onBrowse = { onOpenUrl(AMO_ANDROID_URL) },
                    onInstallFromFile = installFromFile,
                )
            }
            item(key = "installed-header") { SectionHeader(stringResource(R.string.ui_screens_addons_installed)) }
            if (state.addons.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Default.Extension,
                        title = stringResource(R.string.ui_screens_addons_empty_title),
                        body = stringResource(R.string.ui_screens_addons_empty_body),
                    )
                }
            } else {
                items(state.addons, key = { it.id }) { addon ->
                    AddonCard(
                        addon = addon,
                        expanded = expandedId == addon.id,
                        onToggleExpanded = { expandedId = if (expandedId == addon.id) null else addon.id },
                        onEnabledChange = { viewModel.setEnabled(addon.id, it) },
                        onPrivateChange = { viewModel.setAllowedInPrivateBrowsing(addon.id, it) },
                        onOptions = {
                            viewModel.openOptionsPage(addon.id)
                            onBack()
                        },
                        onPopup = {
                            viewModel.openPopup(addon.id)
                            onBack()
                        },
                        onHomepage = addon.homepageUrl?.let { url -> { onOpenUrl(url) } },
                        onRemove = { removeId = addon.id },
                    )
                }
            }
        }
    }

    val removing = removeId?.let { id -> state.addons.firstOrNull { it.id == id } }
    if (removing != null) {
        ConfirmDialog(
            title = stringResource(R.string.ui_screens_addons_remove_title, removing.name),
            text = stringResource(R.string.ui_screens_addons_remove_body),
            confirmLabel = stringResource(R.string.ui_screens_addons_remove),
            onConfirm = { viewModel.uninstall(removing.id) },
            onDismiss = { removeId = null },
        )
    }
}

@Composable
private fun InstallingBanner() {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.ui_screens_addons_installing), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun InstallErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Error, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_screens_addons_install_failed), style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ui_screens_dismiss))
            }
        }
    }
}

@Composable
private fun UBlockCard(installing: Boolean, onInstall: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.ui_screens_addons_ublock_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ui_screens_addons_ublock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onInstall, enabled = !installing) {
                Text(stringResource(R.string.ui_screens_addons_ublock_install))
            }
        }
    }
}

@Composable
private fun InstallSourcesCard(installing: Boolean, onBrowse: () -> Unit, onInstallFromFile: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.ui_screens_addons_signed_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            // Stacked full-width buttons, so neither label is ever cut off (side by side, "Browse add-ons" was
            // truncated on common phone widths and in longer translations).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.ui_screens_addons_browse))
                }
                OutlinedButton(onClick = onInstallFromFile, enabled = !installing, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.ui_screens_addons_from_file))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddonCard(
    addon: AddonInfo,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPrivateChange: (Boolean) -> Unit,
    onOptions: () -> Unit,
    onPopup: () -> Unit,
    onHomepage: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onToggleExpanded)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AddonIcon(addon)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(addon.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    addon.creatorName?.let { stringResource(R.string.ui_screens_addons_version_by, addon.version, it) }
                        ?: stringResource(R.string.ui_screens_addons_version, addon.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = addon.isEnabled,
                onCheckedChange = onEnabledChange,
                enabled = addon.disabledReason == null || addon.isEnabled,
            )
            IconButton(onClick = onToggleExpanded) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.ui_screens_addons_hide_details else R.string.ui_screens_addons_show_details,
                        addon.name,
                    ),
                )
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            addon.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            addon.disabledReason?.let { reason ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    PermissionList(
                        title = stringResource(R.string.ui_screens_addons_permissions),
                        values = addon.permissions,
                    )
                    Spacer(Modifier.height(8.dp))
                    PermissionList(
                        title = stringResource(R.string.ui_screens_addons_site_access),
                        values = addon.origins,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = addon.allowedInPrivateBrowsing,
                                role = Role.Switch,
                                onValueChange = onPrivateChange,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.ui_screens_addons_private), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.ui_screens_addons_private_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = addon.allowedInPrivateBrowsing, onCheckedChange = null)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (addon.optionsPageUrl != null) {
                            TextButton(onClick = onOptions, enabled = addon.isEnabled) {
                                Text(stringResource(R.string.ui_screens_addons_options))
                            }
                        }
                        if (addon.hasBrowserAction) {
                            TextButton(onClick = onPopup, enabled = addon.isEnabled) {
                                Text(stringResource(R.string.ui_screens_addons_open_popup))
                            }
                        }
                        if (onHomepage != null) {
                            TextButton(onClick = onHomepage) { Text(stringResource(R.string.ui_screens_addons_homepage)) }
                        }
                        TextButton(
                            onClick = onRemove,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(stringResource(R.string.ui_screens_addons_remove)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonIcon(addon: AddonInfo) {
    val bitmap = remember(addon.icon) { addon.icon?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PermissionList(title: String, values: List<String>) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(
        if (values.isEmpty()) stringResource(R.string.ui_screens_addons_none) else values.joinToString(separator = "\n"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
