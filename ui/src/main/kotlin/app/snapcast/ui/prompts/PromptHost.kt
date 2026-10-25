package app.snapcast.ui.prompts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snapcast.browser.BrowserPrompt
import app.snapcast.ui.R

/**
 * Renders [prompt] (the oldest pending one) with the matching dialog or system picker. Each prompt gets
 * fresh state (keyed by id) and its callback is invoked at most once.
 */
@Composable
internal fun PromptHost(prompt: BrowserPrompt?) {
    if (prompt == null) return
    key(prompt.id) {
        when (prompt) {
            is BrowserPrompt.Alert -> AlertPrompt(prompt)
            is BrowserPrompt.Confirm -> ConfirmPrompt(prompt)
            is BrowserPrompt.TextInput -> TextInputPrompt(prompt)
            is BrowserPrompt.Auth -> AuthPrompt(prompt)
            is BrowserPrompt.Choice -> ChoicePrompt(prompt)
            is BrowserPrompt.ColorPicker -> ColorPrompt(prompt)
            is BrowserPrompt.DateTime -> DateTimePrompt(prompt)
            is BrowserPrompt.FilePicker -> FilePickerPrompt(prompt)
            is BrowserPrompt.Popup -> PopupPrompt(prompt)
            is BrowserPrompt.BeforeUnload -> BeforeUnloadPrompt(prompt)
            is BrowserPrompt.Repost -> RepostPrompt(prompt)
            is BrowserPrompt.Share -> SharePrompt(prompt)
            is BrowserPrompt.ContentPermission -> ContentPermissionPrompt(prompt)
            is BrowserPrompt.MediaPermission -> MediaPermissionPrompt(prompt)
            is BrowserPrompt.AndroidPermissions -> AndroidPermissionsPrompt(prompt)
        }
    }
}

/** Scrollable message body shared by the simple dialogs. */
@Composable
internal fun PromptMessage(message: String?) {
    if (message.isNullOrBlank()) return
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    )
}

@Composable
internal fun PromptTitle(title: String?) {
    Text(
        text = title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ui_prompt_page_says),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AlertPrompt(prompt: BrowserPrompt.Alert) {
    val once = rememberOnce(prompt.id)
    AlertDialog(
        onDismissRequest = { once(prompt.onDismiss) },
        title = { PromptTitle(prompt.title) },
        text = { PromptMessage(prompt.message) },
        confirmButton = {
            TextButton(onClick = { once(prompt.onDismiss) }) { Text(stringResource(R.string.ui_prompt_ok)) }
        },
    )
}

@Composable
private fun ConfirmPrompt(prompt: BrowserPrompt.Confirm) {
    val once = rememberOnce(prompt.id)
    AlertDialog(
        onDismissRequest = { once { prompt.onResult(null) } },
        title = { PromptTitle(prompt.title) },
        text = { PromptMessage(prompt.message) },
        confirmButton = {
            TextButton(onClick = { once { prompt.onResult(true) } }) { Text(stringResource(R.string.ui_prompt_ok)) }
        },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(false) } }) { Text(stringResource(R.string.ui_prompt_cancel)) }
        },
    )
}

@Composable
private fun TextInputPrompt(prompt: BrowserPrompt.TextInput) {
    val once = rememberOnce(prompt.id)
    var text by rememberSaveable(prompt.id) { mutableStateOf(prompt.defaultValue.orEmpty()) }
    val submit = { once { prompt.onResult(text) } }
    AlertDialog(
        onDismissRequest = { once { prompt.onResult(null) } },
        title = { PromptTitle(prompt.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PromptMessage(prompt.message)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.ui_prompt_ok)) } },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(null) } }) { Text(stringResource(R.string.ui_prompt_cancel)) }
        },
    )
}

@Composable
private fun AuthPrompt(prompt: BrowserPrompt.Auth) {
    val once = rememberOnce(prompt.id)
    var username by rememberSaveable(prompt.id) { mutableStateOf(prompt.defaultUsername.orEmpty()) }
    var password by rememberSaveable(prompt.id) { mutableStateOf("") }
    var showPassword by rememberSaveable(prompt.id) { mutableStateOf(false) }
    val submit = { once { prompt.onResult(if (prompt.passwordOnly) null else username, password) } }
    AlertDialog(
        onDismissRequest = { once { prompt.onResult(null, null) } },
        title = { Text(prompt.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ui_prompt_auth_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PromptMessage(prompt.message)
                if (!prompt.passwordOnly) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.ui_prompt_username)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ui_prompt_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (showPassword) R.string.ui_prompt_hide_password else R.string.ui_prompt_show_password,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.ui_prompt_sign_in)) } },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(null, null) } }) { Text(stringResource(R.string.ui_prompt_cancel)) }
        },
    )
}

@Composable
private fun PopupPrompt(prompt: BrowserPrompt.Popup) {
    val once = rememberOnce(prompt.id)
    AlertDialog(
        onDismissRequest = { once { prompt.onResult(false) } },
        title = { Text(stringResource(R.string.ui_prompt_popup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ui_prompt_popup_message))
                prompt.targetUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { once { prompt.onResult(true) } }) { Text(stringResource(R.string.ui_prompt_allow)) }
        },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(false) } }) { Text(stringResource(R.string.ui_prompt_block)) }
        },
    )
}

@Composable
private fun BeforeUnloadPrompt(prompt: BrowserPrompt.BeforeUnload) {
    val once = rememberOnce(prompt.id)
    AlertDialog(
        onDismissRequest = { once { prompt.onResult(false) } },
        title = { Text(stringResource(R.string.ui_prompt_leave_title)) },
        text = { Text(stringResource(R.string.ui_prompt_leave_message)) },
        confirmButton = {
            TextButton(onClick = { once { prompt.onResult(true) } }) { Text(stringResource(R.string.ui_prompt_leave)) }
        },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(false) } }) { Text(stringResource(R.string.ui_prompt_stay)) }
        },
    )
}

@Composable
private fun RepostPrompt(prompt: BrowserPrompt.Repost) {
    val once = rememberOnce(prompt.id)
    AlertDialog(
        onDismissRequest = { once { prompt.onResult(false) } },
        title = { Text(stringResource(R.string.ui_prompt_repost_title)) },
        text = { Text(stringResource(R.string.ui_prompt_repost_message)) },
        confirmButton = {
            TextButton(onClick = { once { prompt.onResult(true) } }) { Text(stringResource(R.string.ui_prompt_resend)) }
        },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(false) } }) { Text(stringResource(R.string.ui_prompt_cancel)) }
        },
    )
}
