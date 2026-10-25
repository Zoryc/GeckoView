package app.snapcast.ui.history

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapcast.data.history.HistoryEntry
import app.snapcast.ui.R
import app.snapcast.ui.shared.ConfirmDialog
import app.snapcast.ui.shared.EmptyState
import app.snapcast.ui.shared.ScreenScaffold
import app.snapcast.ui.shared.SectionHeader
import app.snapcast.ui.shared.SiteInitial
import app.snapcast.ui.shared.SnackbarMessages
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/** Browsing history grouped by day, with search, per-entry delete and "Clear history". */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(query) { viewModel.setQuery(query) }
    SnackbarMessages(viewModel.messages, snackbarHostState)

    ScreenScaffold(
        title = stringResource(R.string.ui_screens_history_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = { confirmClear = true }, enabled = !(state.isEmpty && query.isBlank())) {
                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.ui_screens_history_clear))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HistorySearchField(query = query, onQueryChange = { query = it })
            HistoryList(
                state = state,
                searching = query.isNotBlank(),
                onOpenUrl = onOpenUrl,
                onDelete = viewModel::delete,
            )
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.ui_screens_history_clear_title),
            text = stringResource(R.string.ui_screens_history_clear_body),
            confirmLabel = stringResource(R.string.ui_screens_clear),
            onConfirm = viewModel::clearAll,
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun HistorySearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.ui_screens_history_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.ui_screens_clear_search))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

@Composable
private fun HistoryList(
    state: HistoryUiState,
    searching: Boolean,
    onOpenUrl: (String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    when {
        !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.isEmpty && searching -> EmptyState(
            icon = Icons.Default.SearchOff,
            title = stringResource(R.string.ui_screens_history_no_results_title),
            body = stringResource(R.string.ui_screens_history_no_results_body, state.query),
        )
        state.isEmpty -> EmptyState(
            icon = Icons.Default.History,
            title = stringResource(R.string.ui_screens_history_empty_title),
            body = stringResource(R.string.ui_screens_history_empty_body),
        )
        else -> {
            val context = LocalContext.current
            val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
            val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
            val today = stringResource(R.string.ui_screens_history_today)
            val yesterday = stringResource(R.string.ui_screens_history_yesterday)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                for (section in state.sections) {
                    item(key = section.key, contentType = "header") {
                        val label = when (val day = section.day) {
                            HistoryDay.Today -> today
                            HistoryDay.Yesterday -> yesterday
                            is HistoryDay.Date -> dateFormatter.format(day.date)
                        }
                        SectionHeader(label)
                    }
                    items(section.entries, key = { it.id }, contentType = { "entry" }) { entry ->
                        HistoryRow(
                            entry = entry,
                            time = timeFormat.format(Date(entry.visitedAt)),
                            onClick = { onOpenUrl(entry.url) },
                            onDelete = { onDelete(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, time: String, onClick: () -> Unit, onDelete: () -> Unit) {
    val title = entry.title.ifBlank { entry.url }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { SiteInitial(url = entry.url, title = entry.title) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                entry.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ui_screens_history_delete, title))
                }
            }
        },
    )
}
