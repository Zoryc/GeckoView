package app.snapcast.ui.local

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapcast.core.media.M3uEntry
import app.snapcast.ui.R
import app.snapcast.ui.shared.EmptyState
import app.snapcast.ui.shared.ScreenScaffold
import app.snapcast.ui.shared.SectionHeader
import app.snapcast.ui.shared.hostFragmentActivity
import app.snapcast.ui.shared.quantityString
import app.snapcast.ui.shared.rememberLocalNetworkGate
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private val VIDEO_MIME_TYPES = arrayOf("video/*")

/** M3U files are labelled inconsistently by file managers, hence the catch-all. */
private val PLAYLIST_MIME_TYPES = arrayOf(
    "audio/x-mpegurl",
    "audio/mpegurl",
    "application/x-mpegurl",
    "application/vnd.apple.mpegurl",
    "text/plain",
    "*/*",
)

/** Cast or play a video stored on the phone, and import an IPTV (.m3u) playlist to cast its channels. */
@Composable
fun LocalMediaScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val viewModel: LocalMediaViewModel = hiltViewModel()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val loading by viewModel.loadingPlaylist.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var urlDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(query) { viewModel.setQuery(query) }
    LocalEffects(viewModel, snackbarHostState)
    val localNetworkGate = rememberLocalNetworkGate()

    val castVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.castLocalVideo(uri)
    }
    val playVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.playLocalVideo(uri)
    }
    val openPlaylist = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPlaylist(uri)
    }
    val pick: (ManagedActivityResultLauncher<Array<String>, Uri?>, Array<String>) -> Unit = { launcher, types ->
        try {
            launcher.launch(types)
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_screens_no_file_picker)) }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.ui_screens_local_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "phone-header") { SectionHeader(stringResource(R.string.ui_screens_local_phone_section)) }
            item(key = "phone-card") {
                PhoneVideoCard(
                    onCast = { localNetworkGate { pick(castVideo, VIDEO_MIME_TYPES) } },
                    onPlay = { pick(playVideo, VIDEO_MIME_TYPES) },
                )
            }
            item(key = "iptv-header") { SectionHeader(stringResource(R.string.ui_screens_local_iptv_section)) }
            item(key = "iptv-card") {
                PlaylistCard(
                    playlist = channels.playlist,
                    loading = loading,
                    onOpenFile = { pick(openPlaylist, PLAYLIST_MIME_TYPES) },
                    onOpenUrl = { urlDialog = true },
                    onClear = {
                        query = ""
                        viewModel.clearPlaylist()
                    },
                )
            }
            if (channels.playlist != null) {
                item(key = "search") { ChannelSearchField(query = query, onQueryChange = { query = it }) }
                channelItems(
                    state = channels,
                    onToggleGroup = viewModel::toggleGroup,
                    onCast = { channel -> localNetworkGate { viewModel.castChannel(channel) } },
                    onPlay = viewModel::playChannel,
                    onQueue = { channel -> localNetworkGate { viewModel.queueChannel(channel) } },
                    onOpenInBrowser = { onOpenUrl(it.url) },
                )
            }
        }
    }

    if (urlDialog) {
        PlaylistUrlDialog(
            onLoad = viewModel::importPlaylistFromUrl,
            onDismiss = { urlDialog = false },
        )
    }
}

@Composable
private fun LocalEffects(viewModel: LocalMediaViewModel, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                LocalEffect.ChooseDevice -> {
                    val activity = context.hostFragmentActivity()
                    if (activity != null) {
                        viewModel.showDeviceChooser(activity)
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.ui_screens_cast_connect_first))
                    }
                }
                is LocalEffect.LaunchPlayer -> try {
                    viewModel.launchPlayer(context, effect.request)
                } catch (e: ActivityNotFoundException) {
                    snackbarHostState.showSnackbar(context.getString(R.string.ui_screens_local_player_unavailable))
                }
                is LocalEffect.Message -> snackbarHostState.showSnackbar(effect.message.resolve(context))
            }
        }
    }
}

@Composable
private fun PhoneVideoCard(onCast: () -> Unit, onPlay: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.VideoFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.ui_screens_local_phone_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCast, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Cast, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.ui_screens_local_cast_video), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.ui_screens_local_play_video), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: IptvPlaylist?,
    loading: Boolean,
    onOpenFile: () -> Unit,
    onOpenUrl: () -> Unit,
    onClear: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.LiveTv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (playlist == null) {
                        Text(
                            stringResource(R.string.ui_screens_local_iptv_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            quantityString(R.plurals.ui_screens_local_channel_count, playlist.channels.size, playlist.channels.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (playlist.skipped > 0) {
                            Text(
                                quantityString(R.plurals.ui_screens_local_skipped, playlist.skipped, playlist.skipped),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (playlist != null) {
                    IconButton(onClick = onClear, enabled = !loading) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.ui_screens_local_clear_playlist))
                    }
                }
            }
            if (loading) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenFile, enabled = !loading, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.ui_screens_local_open_file), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = onOpenUrl, enabled = !loading, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.ui_screens_local_open_url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ChannelSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        placeholder = { Text(stringResource(R.string.ui_screens_local_search_hint)) },
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

private fun LazyListScope.channelItems(
    state: ChannelListState,
    onToggleGroup: (String) -> Unit,
    onCast: (M3uEntry) -> Unit,
    onPlay: (M3uEntry) -> Unit,
    onQueue: (M3uEntry) -> Unit,
    onOpenInBrowser: (M3uEntry) -> Unit,
) {
    if (state.groups.isEmpty()) {
        item(key = "no-channels") {
            EmptyState(
                icon = Icons.Default.SearchOff,
                title = stringResource(R.string.ui_screens_local_no_matches_title),
                body = stringResource(R.string.ui_screens_local_no_matches_body, state.query),
            )
        }
        return
    }
    state.groups.forEachIndexed { groupIndex, group ->
        val collapsed = group.key in state.collapsedGroups
        item(key = "group|$groupIndex", contentType = "group") {
            GroupHeader(
                name = group.name ?: stringResource(R.string.ui_screens_local_ungrouped),
                count = group.channels.size,
                collapsed = collapsed,
                onToggle = { onToggleGroup(group.key) },
            )
        }
        if (!collapsed) {
            itemsIndexed(
                group.channels,
                key = { index, _ -> "channel|$groupIndex|$index" },
                contentType = { _, _ -> "channel" },
            ) { _, channel ->
                ChannelRow(
                    channel = channel,
                    onCast = { onCast(channel) },
                    onPlay = { onPlay(channel) },
                    onQueue = { onQueue(channel) },
                    onOpenInBrowser = { onOpenInBrowser(channel) },
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(name: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ui_screens_local_group, name, count),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            contentDescription = stringResource(
                if (collapsed) R.string.ui_screens_local_expand_group else R.string.ui_screens_local_collapse_group,
                name,
            ),
        )
    }
}

@Composable
private fun ChannelRow(
    channel: M3uEntry,
    onCast: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val type = IptvChannels.streamType(channel.url)
    ListItem(
        modifier = Modifier.clickable(onClick = onCast),
        leadingContent = { ChannelLogo(channel.logoUrl) },
        headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                type.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCast) {
                    Icon(Icons.Default.Cast, contentDescription = stringResource(R.string.ui_screens_local_cast_channel, channel.name))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.ui_screens_local_channel_actions, channel.name),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_screens_local_play_on_phone)) },
                            leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onPlay()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_screens_local_add_to_queue)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onQueue()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_screens_local_open_in_browser)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenInBrowser()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ChannelLogo(logoUrl: String?) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.LiveTv, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Asks for a playlist URL; [onLoad] returns false when the text is not an http(s) URL. */
@Composable
private fun PlaylistUrlDialog(onLoad: (String) -> Boolean, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val submit: () -> Unit = {
        if (onLoad(text)) onDismiss() else invalid = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_screens_local_url_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    invalid = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ui_screens_local_url_hint)) },
                singleLine = true,
                isError = invalid,
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.ui_screens_local_url_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = text.isNotBlank()) { Text(stringResource(R.string.ui_screens_local_load)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_screens_cancel)) }
        },
    )
}
