package app.snapcast.ui.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.snapcast.browser.BrowserPrompt
import app.snapcast.ui.R
import app.snapcast.ui.util.ColorHex

private val Palette = listOf(
    "#000000", "#5f6368", "#9aa0a6", "#ffffff",
    "#d93025", "#e8710a", "#f9ab00", "#fdd663",
    "#188038", "#34a853", "#12b5cb", "#1a73e8",
    "#4285f4", "#9334e6", "#c58af9", "#e52592",
    "#795548", "#ff8a65", "#80deea", "#a7ffeb",
)

/** `<input type=color>`: a palette of common colors plus a hex field with a live preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorPrompt(prompt: BrowserPrompt.ColorPicker) {
    val once = rememberOnce(prompt.id)
    var hex by rememberSaveable(prompt.id) { mutableStateOf(ColorHex.normalize(prompt.defaultValue) ?: "#000000") }
    val normalized = ColorHex.normalize(hex)
    val submit: () -> Unit = { if (normalized != null) once { prompt.onResult(normalized) } }
    AlertDialog(
        onDismissRequest = { once { prompt.onResult(null) } },
        title = { Text(stringResource(R.string.ui_prompt_color_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Palette.forEach { swatch ->
                        Swatch(hex = swatch, selected = swatch == normalized, onClick = { hex = swatch })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ColorHex.toArgb(normalized)?.let { Color(it) } ?: Color.Transparent)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { hex = it.take(7) },
                        label = { Text(stringResource(R.string.ui_prompt_color_hex)) },
                        singleLine = true,
                        isError = normalized == null,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = normalized != null) { Text(stringResource(R.string.ui_prompt_ok)) }
        },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(null) } }) { Text(stringResource(R.string.ui_prompt_cancel)) }
        },
    )
}

@Composable
private fun Swatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val color = ColorHex.toArgb(hex)?.let { Color(it) } ?: Color.Transparent
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = hex },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
