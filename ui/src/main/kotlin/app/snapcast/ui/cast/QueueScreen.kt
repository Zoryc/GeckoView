package app.snapcast.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapcast.ui.R
import app.snapcast.ui.components.rememberCastPosterRequest
import app.snapcast.ui.shared.EmptyState
import app.snapcast.ui.shared.ScreenScaffold
import app.snapcast.ui.shared.SectionHeader
import app.snapcast.ui.shared.hostFragmentActivity
import app.snapcast.ui.shared.rememberLocalNetworkGate
import coil3.compose.AsyncImage

/** The receiver queue while casting (jump, play next, reorder, remove), otherwise the pending queue. */
@Composable
fun QueueScreen(onBack: () -> Unit) {
    val viewModel: QueueViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val starting by viewModel.startingCast.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val localNetworkGate = rememberLocalNetworkGate()

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                QueueEffect.ChooseDevice -> {
                    val activity = context.hostFragmentActivity()
                    if (activity != null) {
                        viewModel.showDeviceChooser(activity)
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.ui_screens_cast_connect_first))
                    }
                }
                is QueueEffect.Message -> snackbarHostState.showSnackbar(effect.message.resolve(context))
            }
        }
    }

    val pending = state as? QueueUiState.Pending
    ScreenScaffold(
        title = stringResource(R.string.ui_screens_queue_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            if (pending != null && pending.items.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { if (!starting) localNetworkGate { viewModel.castQueue() } },
                    icon = {
                        if (starting) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Cast, contentDescription = null)
                        }
                    },
                    text = { Text(stringResource(R.string.ui_screens_queue_cast)) },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val current = state) {
                is QueueUiState.Remote -> RemoteQueue(current, viewModel, Modifier.align(Alignment.Center))
                is QueueUiState.Pending -> PendingQueue(current, viewModel, Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun RemoteQueue(state: QueueUiState.Remote, viewModel: QueueViewModel, emptyModifier: Modifier) {
    if (state.items.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            title = stringResource(R.string.ui_screens_queue_empty_title),
            body = stringResource(R.string.ui_screens_queue_empty_body),
            modifier = emptyModifier,
        )
        return
    }
    val ids = state.items.map { it.itemId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") { SectionHeader(stringResource(R.string.ui_screens_queue_on_receiver)) }
        itemsIndexed(state.items, key = { _, item -> item.itemId }) { index, item ->
            val isCurrent = item.itemId == state.currentItemId
            QueueRow(
                title = item.title,
                subtitle = item.subtitle,
                posterUrl = item.posterUrl,
                isCurrent = isCurrent,
                onClick = { if (!isCurrent) viewModel.jumpTo(item.itemId) },
                canPlayNext = QueueMath.playNextIndex(ids, item.itemId, state.currentItemId) != null,
                onPlayNext = { viewModel.playNext(item.itemId) },
                canMoveUp = index > 0,
                canMoveDown = index < state.items.lastIndex,
                onMoveUp = { viewModel.moveRemote(item.itemId, -1) },
                onMoveDown = { viewModel.moveRemote(item.itemId, 1) },
                onRemove = { viewModel.removeRemote(item.itemId) },
            )
        }
    }
}

@Composable
private fun PendingQueue(state: QueueUiState.Pending, viewModel: QueueViewModel, emptyModifier: Modifier) {
    if (state.items.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            title = stringResource(R.string.ui_screens_queue_empty_title),
            body = stringResource(R.string.ui_screens_queue_empty_body),
            modifier = emptyModifier,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "header") {
            Column {
                SectionHeader(stringResource(R.string.ui_screens_queue_pending))
                Text(
                    stringResource(R.string.ui_screens_queue_pending_help),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(state.items, key = { _, item -> item.key }) { index, item ->
            val video = item.video
            QueueRow(
                title = video.displayTitle,
                subtitle = listOf(video.type.label, video.sourceDomain).filter { it.isNotBlank() }.joinToString(" · "),
                posterUrl = video.posterUrl,
                isCurrent = false,
                onClick = null,
                canPlayNext = false,
                onPlayNext = {},
                canMoveUp = index > 0,
                canMoveDown = index < state.items.lastIndex,
                onMoveUp = { viewModel.movePending(item.key, -1) },
                onMoveDown = { viewModel.movePending(item.key, 1) },
                onRemove = { viewModel.removePending(item.key) },
            )
        }
    }
}

@Composable
private fun QueueRow(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    isCurrent: Boolean,
    onClick: (() -> Unit)?,
    canPlayNext: Boolean,
    onPlayNext: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = if (isCurrent) {
        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        ListItemDefaults.colors()
    }
    Column {
        ListItem(
            modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            colors = colors,
            leadingContent = { QueueThumbnail(posterUrl = posterUrl, isCurrent = isCurrent) },
            headlineContent = {
                Text(
                    title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                )
            },
            supportingContent = {
                val text = if (isCurrent) {
                    stringResource(R.string.ui_screens_queue_now_playing)
                } else {
                    subtitle?.takeIf { it.isNotBlank() }
                }
                if (text != null) Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.ui_screens_queue_item_actions, title))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (canPlayNext) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_screens_queue_play_next)) },
                                leadingIcon = { Icon(Icons.Default.QueuePlayNext, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onPlayNext()
                                },
                            )
                        }
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
                            text = { Text(stringResource(R.string.ui_screens_queue_remove)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            },
        )
        HorizontalDivider()
    }
}

@Composable
private fun QueueThumbnail(posterUrl: String?, isCurrent: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isCurrent) Icons.Default.Equalizer else Icons.Default.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (posterUrl != null && !isCurrent) {
            AsyncImage(
                model = rememberCastPosterRequest(posterUrl),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
