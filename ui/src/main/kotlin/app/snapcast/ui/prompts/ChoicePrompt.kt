package app.snapcast.ui.prompts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.snapcast.browser.BrowserPrompt
import app.snapcast.ui.R

/** A [BrowserPrompt.ChoiceItem] placed in the flattened list with its nesting depth. */
private data class ChoiceRow(val item: BrowserPrompt.ChoiceItem, val depth: Int)

private fun flatten(items: List<BrowserPrompt.ChoiceItem>, depth: Int = 0): List<ChoiceRow> =
    items.flatMap { item ->
        listOf(ChoiceRow(item, depth)) + if (item.isGroup) flatten(item.children, depth + 1) else emptyList()
    }

/**
 * `<select>` and menus. Single selection resolves on tap; multiple selection uses checkboxes and an OK
 * button. Groups (`<optgroup>`) are shown as headers with their options indented; separators as dividers.
 */
@Composable
internal fun ChoicePrompt(prompt: BrowserPrompt.Choice) {
    val once = rememberOnce(prompt.id)
    val rows = remember(prompt.id) { flatten(prompt.items) }
    val selected = remember(prompt.id) {
        mutableStateListOf<String>().apply {
            addAll(rows.filter { !it.item.isGroup && !it.item.isSeparator && it.item.selected }.map { it.item.id })
        }
    }
    val title = prompt.title?.takeIf { it.isNotBlank() }
    AlertDialog(
        onDismissRequest = { once { prompt.onResult(null) } },
        title = if (title != null) {
            { Text(title) }
        } else {
            null
        },
        text = {
            Column {
                PromptMessage(prompt.message)
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(rows, key = { index, row -> "$index:${row.item.id}" }) { _, row ->
                        val item = row.item
                        val indent = (row.depth * 16).dp
                        when {
                            item.isSeparator -> HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            item.isGroup -> Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = indent, top = 12.dp, bottom = 4.dp),
                            )
                            prompt.multiple -> Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = item.id in selected,
                                        enabled = item.enabled,
                                        role = Role.Checkbox,
                                        onValueChange = { checked ->
                                            if (checked) selected.add(item.id) else selected.remove(item.id)
                                        },
                                    )
                                    .padding(start = indent, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = item.id in selected, onCheckedChange = null, enabled = item.enabled)
                                Spacer(Modifier.width(12.dp))
                                ChoiceLabel(item)
                            }
                            else -> Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = item.enabled, role = Role.RadioButton) {
                                        once { prompt.onResult(listOf(item.id)) }
                                    }
                                    .padding(start = indent, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = item.selected, onClick = null, enabled = item.enabled)
                                Spacer(Modifier.width(12.dp))
                                ChoiceLabel(item)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (prompt.multiple) {
                TextButton(onClick = { once { prompt.onResult(selected.toList()) } }) {
                    Text(stringResource(R.string.ui_prompt_ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { once { prompt.onResult(null) } }) { Text(stringResource(R.string.ui_prompt_cancel)) }
        },
    )
}

@Composable
private fun ChoiceLabel(item: BrowserPrompt.ChoiceItem) {
    Text(
        text = item.label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (item.enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
    )
}
