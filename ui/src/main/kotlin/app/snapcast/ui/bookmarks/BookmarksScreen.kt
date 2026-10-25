package app.snapcast.ui.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapcast.data.bookmarks.Bookmark
import app.snapcast.ui.R
import app.snapcast.ui.shared.EmptyState
import app.snapcast.ui.shared.ScreenScaffold
import app.snapcast.ui.shared.SiteInitial

/** Bookmarks list: open, edit (title, URL, speed dial), reorder, delete with undo, add. */
@Composable
fun BookmarksScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val viewModel: BookmarksViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    BookmarkEvents(viewModel, snackbarHostState)

    ScreenScaffold(
        title = stringResource(R.string.ui_screens_bookmarks_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.ui_screens_bookmarks_add)) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.bookmarks.isEmpty() -> EmptyState(
                    icon = Icons.Default.Bookmarks,
                    title = stringResource(R.string.ui_screens_bookmarks_empty_title),
                    body = stringResource(R.string.ui_screens_bookmarks_empty_body),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    itemsIndexed(state.bookmarks, key = { _, bookmark -> bookmark.id }) { index, bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.bookmarks.lastIndex,
                            onOpen = { onOpenUrl(bookmark.url) },
                            onEdit = { editingId = bookmark.id },
                            onMoveUp = { viewModel.moveUp(bookmark.id) },
                            onMoveDown = { viewModel.moveDown(bookmark.id) },
                            onToggleHome = { viewModel.setOnHome(bookmark, !bookmark.onHome) },
                            onDelete = { viewModel.delete(bookmark) },
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        BookmarkDialog(
            title = stringResource(R.string.ui_screens_bookmarks_add),
            initialTitle = "",
            initialUrl = "",
            initialOnHome = false,
            onSave = { url, title, onHome -> viewModel.add(url, title, onHome) },
            onDismiss = { adding = false },
        )
    }
    val editing = editingId?.let { id -> state.bookmarks.firstOrNull { it.id == id } }
    if (editing != null) {
        BookmarkDialog(
            title = stringResource(R.string.ui_screens_bookmarks_edit),
            initialTitle = editing.title,
            initialUrl = editing.url,
            initialOnHome = editing.onHome,
            onSave = { url, title, onHome -> viewModel.save(editing.id, url, title, onHome) },
            onDismiss = { editingId = null },
        )
    } else if (editingId != null && state.loaded) {
        // The bookmark was removed while its dialog was open.
        LaunchedEffect(editingId) { editingId = null }
    }
}

@Composable
private fun BookmarkEvents(viewModel: BookmarksViewModel, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collect { event ->
            when (event) {
                is BookmarkEvent.Added ->
                    snackbarHostState.showSnackbar(context.getString(R.string.ui_screens_bookmarks_added, event.title))
                BookmarkEvent.Saved -> snackbarHostState.showSnackbar(context.getString(R.string.ui_screens_bookmarks_saved))
                is BookmarkEvent.Deleted -> {
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.ui_screens_bookmarks_deleted, event.bookmark.title),
                        actionLabel = context.getString(R.string.ui_screens_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.bookmark)
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleHome: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column {
        ListItem(
            modifier = Modifier.clickable(onClick = onOpen),
            leadingContent = { SiteInitial(url = bookmark.url, title = bookmark.title) },
            headlineContent = { Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    bookmark.url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bookmark.onHome) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = stringResource(R.string.ui_screens_bookmarks_on_home),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.ui_screens_bookmarks_actions, bookmark.title),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_screens_edit)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (bookmark.onHome) {
                                                R.string.ui_screens_bookmarks_remove_from_home
                                            } else {
                                                R.string.ui_screens_bookmarks_add_to_home
                                            },
                                        ),
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onToggleHome()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_screens_move_up)) },
                                leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                                enabled = canMoveUp,
                                onClick = {
                                    menuOpen = false
                                    onMoveUp()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_screens_move_down)) },
                                leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                                enabled = canMoveDown,
                                onClick = {
                                    menuOpen = false
                                    onMoveDown()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_screens_delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            },
        )
        HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
    }
}

/** Add / edit dialog. [onSave] receives the normalized URL, the title and the speed-dial choice. */
@Composable
private fun BookmarkDialog(
    title: String,
    initialTitle: String,
    initialUrl: String,
    initialOnHome: Boolean,
    onSave: (url: String, title: String, onHome: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialTitle) }
    var address by rememberSaveable { mutableStateOf(initialUrl) }
    var onHome by rememberSaveable { mutableStateOf(initialOnHome) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val normalizedUrl = BookmarkInput.normalizeUrl(address)
    val urlError = showErrors && normalizedUrl == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ui_screens_bookmarks_field_title)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ui_screens_bookmarks_field_url)) },
                    singleLine = true,
                    isError = urlError,
                    supportingText = if (urlError) {
                        { Text(stringResource(R.string.ui_screens_bookmarks_invalid_url)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHome = !onHome }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ui_screens_bookmarks_show_on_home),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(checked = onHome, onCheckedChange = { onHome = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (normalizedUrl == null) {
                        showErrors = true
                    } else {
                        onSave(normalizedUrl, name, onHome)
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.ui_screens_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_screens_cancel)) }
        },
    )
}
