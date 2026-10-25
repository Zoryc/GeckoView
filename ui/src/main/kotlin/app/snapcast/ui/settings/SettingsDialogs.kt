package app.snapcast.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.snapcast.core.model.AppSettings
import app.snapcast.ui.R

/** Built-in speed dial or a custom URL. [onSave] gets null for the built-in page; it returns false for a bad URL. */
@Composable
internal fun HomePageDialog(
    currentHomePage: String,
    onSave: (customUrl: String?) -> Boolean,
    onDismiss: () -> Unit,
) {
    val isBuiltIn = currentHomePage == AppSettings.HOME_PAGE_BUILT_IN
    var custom by rememberSaveable { mutableStateOf(!isBuiltIn) }
    var url by rememberSaveable { mutableStateOf(if (isBuiltIn) "" else currentHomePage) }
    var invalid by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_screens_settings_home_page)) },
        text = {
            Column(Modifier.selectableGroup()) {
                RadioRow(
                    text = stringResource(R.string.ui_screens_settings_home_builtin),
                    selected = !custom,
                    onClick = { custom = false },
                )
                RadioRow(
                    text = stringResource(R.string.ui_screens_settings_home_custom),
                    selected = custom,
                    onClick = { custom = true },
                )
                if (custom) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            invalid = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_screens_settings_home_url)) },
                        singleLine = true,
                        isError = invalid,
                        supportingText = if (invalid) {
                            { Text(stringResource(R.string.ui_screens_settings_home_invalid)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onSave(if (custom) url else null)) onDismiss() else invalid = true
                },
            ) { Text(stringResource(R.string.ui_screens_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_screens_cancel)) }
        },
    )
}

/** Editor for [AppSettings.ignoredDomains]; changes apply immediately. */
@Composable
internal fun IgnoredDomainsDialog(
    domains: Set<String>,
    onAdd: (String) -> DomainCheck,
    onRemove: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val invalidText = stringResource(R.string.ui_screens_settings_domain_invalid)
    val duplicateFormat = stringResource(R.string.ui_screens_settings_domain_duplicate)
    val submit: () -> Unit = {
        when (val result = onAdd(input)) {
            is DomainCheck.Valid -> {
                input = ""
                error = null
            }
            DomainCheck.Invalid -> error = invalidText
            is DomainCheck.Duplicate -> error = duplicateFormat.format(result.domain)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_screens_settings_ignored_domains)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.ui_screens_settings_ignored_domains_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ui_screens_settings_domain_hint)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    trailingIcon = {
                        IconButton(onClick = submit, enabled = input.isNotBlank()) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ui_screens_settings_domain_add))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) submit() }),
                )
                Spacer(Modifier.height(8.dp))
                if (domains.isEmpty()) {
                    Text(
                        stringResource(R.string.ui_screens_settings_domains_empty),
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        domains.sorted().forEach { domain ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(domain, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = { onRemove(domain) }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.ui_screens_settings_domain_remove, domain),
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_screens_done)) }
        },
        dismissButton = {
            TextButton(onClick = onRestoreDefaults) { Text(stringResource(R.string.ui_screens_settings_domains_restore)) }
        },
    )
}

/** Number field for the proxy port with live validation (1024..65535). */
@Composable
internal fun ProxyPortDialog(currentPort: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(currentPort.toString()) }
    val check = PortValidation.validate(text)
    val errorText = when ((check as? PortCheck.Invalid)?.error) {
        PortError.EMPTY -> stringResource(R.string.ui_screens_settings_port_empty)
        PortError.NOT_A_NUMBER -> stringResource(R.string.ui_screens_settings_port_not_number)
        PortError.OUT_OF_RANGE -> stringResource(
            R.string.ui_screens_settings_port_range,
            PortValidation.MIN_PORT,
            PortValidation.MAX_PORT,
        )
        null -> null
    }
    val save: () -> Unit = {
        if (check is PortCheck.Valid) {
            onSave(check.port)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_screens_settings_proxy_port)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.ui_screens_settings_proxy_port_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { value -> text = value.filter { it.isDigit() }.take(MAX_PORT_DIGITS) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ui_screens_settings_proxy_port)) },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = check is PortCheck.Valid) { Text(stringResource(R.string.ui_screens_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_screens_cancel)) }
        },
    )
}

private const val MAX_PORT_DIGITS = 5

/** Open-source notice: the third-party components the app ships and their licenses. */
@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_screens_settings_licenses)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.ui_screens_settings_licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OpenSourceNotices.COMPONENTS.forEach { component ->
                    Spacer(Modifier.height(12.dp))
                    Text(component.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        component.license,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_screens_close)) }
        },
    )
}
