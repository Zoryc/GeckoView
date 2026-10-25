package app.snapcast.ui.cast

import android.content.ActivityNotFoundException
import android.content.Context
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.CastTextTrack
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.cast.RemotePlayerState
import app.snapcast.core.util.Formatters
import app.snapcast.ui.R
import app.snapcast.ui.shared.EmptyState
import app.snapcast.ui.shared.SnackbarMessages
import app.snapcast.ui.shared.semanticsLabel
import coil3.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.launch

private val SUBTITLE_MIME_TYPES = arrayOf("application/x-subrip", "text/vtt", "text/plain", "*/*")

/** Expanded cast controller; shows how to connect when nothing is casting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastControllerScreen(onBack: () -> Unit, onOpenQueue: () -> Unit) {
    val viewModel: CastControllerViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    SnackbarMessages(viewModel.messages, snackbarHostState)

    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadSubtitle(uri)
    }
    val loadSubtitle: () -> Unit = {
        try {
            pickSubtitle.launch(SUBTITLE_MIME_TYPES)
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_screens_no_file_picker)) }
        }
    }
    val connected = state.connection == CastConnectionState.CONNECTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.device?.name?.takeIf { connected } ?: stringResource(R.string.ui_screens_cast_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_screens_navigate_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = stringResource(R.string.ui_screens_cast_queue))
                    }
                    if (state.connection != CastConnectionState.UNAVAILABLE) {
                        AndroidView(
                            factory = { viewContext -> viewModel.createCastButton(viewContext) },
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    if (connected) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.ui_screens_more_options))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ui_screens_cast_disconnect)) },
                                    leadingIcon = { Icon(Icons.Default.CastConnected, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.disconnect()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val playback = state.playback
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (playback == null) {
                NotCasting(
                    state = state,
                    createCastButton = viewModel::createCastButton,
                    onDisconnect = viewModel::disconnect,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ExpandedController(
                    playback = playback,
                    deviceName = state.device?.name,
                    viewModel = viewModel,
                    onOpenQueue = onOpenQueue,
                    onLoadSubtitle = loadSubtitle,
                )
            }
        }
    }
}

@Composable
private fun NotCasting(
    state: CastScreenState,
    createCastButton: (Context) -> View,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deviceName = state.device?.name ?: stringResource(R.string.ui_screens_cast_device_fallback)
    val (title, body) = when (state.connection) {
        CastConnectionState.UNAVAILABLE ->
            stringResource(R.string.ui_screens_cast_unavailable_title) to stringResource(R.string.ui_screens_cast_unavailable_body)
        CastConnectionState.NO_DEVICES ->
            stringResource(R.string.ui_screens_cast_no_devices_title) to stringResource(R.string.ui_screens_cast_same_wifi)
        CastConnectionState.NOT_CONNECTED ->
            stringResource(R.string.ui_screens_cast_not_connected_title) to stringResource(R.string.ui_screens_cast_not_connected_body)
        CastConnectionState.CONNECTING ->
            stringResource(R.string.ui_screens_cast_connecting_title) to stringResource(R.string.ui_screens_cast_same_wifi)
        CastConnectionState.CONNECTED ->
            stringResource(R.string.ui_screens_cast_connected_title, deviceName) to
                stringResource(R.string.ui_screens_cast_connected_body)
    }
    EmptyState(
        icon = if (state.connection == CastConnectionState.CONNECTED) Icons.Default.CastConnected else Icons.Default.Cast,
        title = title,
        body = body,
        modifier = modifier,
        action = if (state.connection == CastConnectionState.UNAVAILABLE) {
            null
        } else {
            {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        AndroidView(
                            factory = { viewContext -> createCastButton(viewContext) },
                            modifier = Modifier
                                .padding(8.dp)
                                .size(48.dp),
                        )
                    }
                    if (state.connection == CastConnectionState.CONNECTING) {
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator()
                    }
                    if (state.connection == CastConnectionState.CONNECTED) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.ui_screens_cast_disconnect)) }
                    }
                }
            }
        },
    )
}

@Composable
private fun ExpandedController(
    playback: RemotePlaybackState,
    deviceName: String?,
    viewModel: CastControllerViewModel,
    onOpenQueue: () -> Unit,
    onLoadSubtitle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(posterUrl = playback.posterUrl, modifier = Modifier.widthIn(max = 560.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            playback.title ?: stringResource(R.string.ui_screens_cast_untitled),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        playback.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (deviceName != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CastConnected,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.ui_screens_cast_casting_to, deviceName),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        PlayerStatus(playback)
        Spacer(Modifier.height(16.dp))
        SeekBar(playback = playback, onSeek = viewModel::seekTo)
        Spacer(Modifier.height(8.dp))
        TransportControls(playback = playback, viewModel = viewModel)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = viewModel::stop) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ui_screens_cast_stop))
            }
            OutlinedButton(onClick = onOpenQueue) {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (playback.queue.size > 1) {
                        stringResource(R.string.ui_screens_cast_queue_count, playback.queue.size)
                    } else {
                        stringResource(R.string.ui_screens_cast_queue)
                    },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        VolumeControl(playback = playback, onVolume = viewModel::setVolume, onMute = viewModel::setMuted)
        if (playback.supportsPlaybackRate) {
            Spacer(Modifier.height(12.dp))
            SpeedSelector(rate = playback.playbackRate, onRate = viewModel::setPlaybackRate)
        }
        Spacer(Modifier.height(12.dp))
        SubtitleSelector(
            tracks = playback.textTracks,
            activeTrackId = playback.activeTextTrackId,
            onSelect = viewModel::setTextTrack,
            onLoadFile = onLoadSubtitle,
        )
    }
}

@Composable
private fun Artwork(posterUrl: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Movie,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = stringResource(R.string.ui_screens_cast_artwork),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun PlayerStatus(playback: RemotePlaybackState) {
    val status = when {
        playback.playerState == RemotePlayerState.LOADING -> stringResource(R.string.ui_screens_cast_loading)
        playback.playerState == RemotePlayerState.BUFFERING -> stringResource(R.string.ui_screens_cast_buffering)
        playback.playerState == RemotePlayerState.IDLE && playback.idleReason == IDLE_ERROR ->
            stringResource(R.string.ui_screens_cast_receiver_error)
        playback.playerState == RemotePlayerState.IDLE && playback.idleReason == IDLE_FINISHED ->
            stringResource(R.string.ui_screens_cast_finished)
        else -> null
    } ?: return
    Spacer(Modifier.height(8.dp))
    Text(
        status,
        style = MaterialTheme.typography.bodyMedium,
        color = if (playback.idleReason == IDLE_ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val IDLE_ERROR = "ERROR"
private const val IDLE_FINISHED = "FINISHED"

@Composable
private fun SeekBar(playback: RemotePlaybackState, onSeek: (Long) -> Unit) {
    if (playback.isLive) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.error) {
                Text(
                    stringResource(R.string.ui_screens_cast_live),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onError,
                )
            }
            if (playback.positionMs > 0) {
                Spacer(Modifier.width(12.dp))
                Text(Formatters.durationMs(playback.positionMs), style = MaterialTheme.typography.labelLarge)
            }
        }
        return
    }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs
    val fraction = dragFraction ?: CastControls.fraction(playback.positionMs, duration)
    val shownPosition = dragFraction?.let { CastControls.positionAt(it, duration) } ?: playback.positionMs
    val seekDescription = stringResource(R.string.ui_screens_cast_seek)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek(CastControls.positionAt(it, duration)) }
                dragFraction = null
            },
            enabled = playback.isSeekable && duration > 0,
            modifier = Modifier
                .fillMaxWidth()
                .semanticsLabel(seekDescription),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(Formatters.durationMs(shownPosition), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            if (duration > 0) {
                Text(
                    "-" + Formatters.durationMs(CastControls.remainingMs(shownPosition, duration)),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun TransportControls(playback: RemotePlaybackState, viewModel: CastControllerViewModel) {
    val ids = playback.queue.map { it.itemId }
    val (hasPrevious, hasNext) = QueueMath.neighbours(ids, playback.currentItemId)
    val showQueueButtons = playback.queue.size > 1
    val canSeek = playback.isSeekable && !playback.isLive
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showQueueButtons) {
            IconButton(onClick = viewModel::skipPrevious, enabled = hasPrevious) {
                Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.ui_screens_cast_previous))
            }
        }
        IconButton(onClick = viewModel::rewind, enabled = canSeek) {
            Icon(
                Icons.Default.Replay10,
                contentDescription = stringResource(R.string.ui_screens_cast_rewind),
                modifier = Modifier.size(32.dp),
            )
        }
        val playing = playback.playerState == RemotePlayerState.PLAYING
        val busy = playback.playerState == RemotePlayerState.LOADING || playback.playerState == RemotePlayerState.BUFFERING
        FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(72.dp)) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.ui_screens_cast_pause else R.string.ui_screens_cast_play),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        IconButton(onClick = viewModel::fastForward, enabled = canSeek) {
            Icon(
                Icons.Default.Forward10,
                contentDescription = stringResource(R.string.ui_screens_cast_forward),
                modifier = Modifier.size(32.dp),
            )
        }
        if (showQueueButtons) {
            IconButton(onClick = viewModel::skipNext, enabled = hasNext) {
                Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.ui_screens_cast_next))
            }
        }
    }
}

@Composable
private fun VolumeControl(playback: RemotePlaybackState, onVolume: (Float) -> Unit, onMute: (Boolean) -> Unit) {
    var dragVolume by remember { mutableStateOf<Float?>(null) }
    val volumeDescription = stringResource(R.string.ui_screens_cast_volume)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMute(!playback.isMuted) }) {
            Icon(
                if (playback.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(if (playback.isMuted) R.string.ui_screens_cast_unmute else R.string.ui_screens_cast_mute),
            )
        }
        Slider(
            value = dragVolume ?: playback.volume.toFloat().coerceIn(0f, 1f),
            onValueChange = { dragVolume = it },
            onValueChangeFinished = {
                dragVolume?.let(onVolume)
                dragVolume = null
            },
            modifier = Modifier
                .weight(1f)
                .semanticsLabel(volumeDescription),
        )
    }
}

@Composable
private fun SpeedSelector(rate: Double, onRate: (Double) -> Unit) {
    val selected = CastControls.nearestRate(rate)
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ui_screens_cast_speed), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CastControls.PLAYBACK_RATES.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onRate(option) },
                    label = { Text(CastControls.rateLabel(option)) },
                )
            }
        }
    }
}

@Composable
private fun SubtitleSelector(
    tracks: List<CastTextTrack>,
    activeTrackId: Long?,
    onSelect: (Long?) -> Unit,
    onLoadFile: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val offLabel = stringResource(R.string.ui_screens_cast_subtitles_off)
    val active = tracks.firstOrNull { it.id == activeTrackId }
    val activeLabel = active?.let { trackLabel(it, tracks.indexOf(it)) } ?: offLabel
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Subtitles, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.ui_screens_cast_subtitles),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(activeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(offLabel) },
                    onClick = {
                        expanded = false
                        onSelect(null)
                    },
                )
                tracks.forEachIndexed { index, track ->
                    DropdownMenuItem(
                        text = { Text(trackLabel(track, index)) },
                        onClick = {
                            expanded = false
                            onSelect(track.id)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_screens_cast_subtitles_load)) },
                    onClick = {
                        expanded = false
                        onLoadFile()
                    },
                )
            }
        }
    }
}

@Composable
private fun trackLabel(track: CastTextTrack, index: Int): String {
    track.name?.takeIf { it.isNotBlank() }?.let { return it }
    val language = track.language?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it).displayName }
    return language?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ui_screens_cast_subtitle_track, index + 1)
}
