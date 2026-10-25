package app.snapcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.cast.RemotePlayerState
import app.snapcast.core.util.Formatters
import app.snapcast.ui.R
import app.snapcast.ui.theme.SnapcastThemeExtras
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Compact "now casting" bar: poster, title, receiver name, progress and play/pause.
 * Tapping it opens the expanded controller.
 */
@Composable
fun MiniController(
    playback: RemotePlaybackState,
    deviceName: String?,
    onTogglePlayPause: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openLabel = stringResource(R.string.ui_mini_open_controller)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = openLabel, onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column {
            val duration = playback.durationMs
            if (!playback.isLive && duration > 0) {
                val fraction = (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniPoster(playback.posterUrl)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playback.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ui_mini_untitled),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (playback.isLive) LiveTag()
                        Text(
                            text = statusLine(playback, deviceName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(contentAlignment = Alignment.Center) {
                    val busy = playback.playerState == RemotePlayerState.LOADING ||
                        playback.playerState == RemotePlayerState.BUFFERING
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(44.dp), strokeWidth = 2.dp)
                    val playing = playback.playerState == RemotePlayerState.PLAYING ||
                        playback.playerState == RemotePlayerState.BUFFERING
                    FilledTonalIconButton(onClick = onTogglePlayPause) {
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.ui_mini_pause else R.string.ui_mini_play),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLine(playback: RemotePlaybackState, deviceName: String?): String {
    val device = deviceName?.takeIf { it.isNotBlank() }
    val casting = if (device != null) {
        stringResource(R.string.ui_mini_casting_to, device)
    } else {
        stringResource(R.string.ui_mini_casting)
    }
    if (playback.isLive || playback.durationMs <= 0) return casting
    return "$casting · ${Formatters.durationMs(playback.positionMs)} / ${Formatters.durationMs(playback.durationMs)}"
}

@Composable
private fun MiniPoster(posterUrl: String?) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CastConnected,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(posterUrl).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Small red "LIVE" tag. */
@Composable
fun LiveTag(modifier: Modifier = Modifier) {
    val colors = SnapcastThemeExtras.colors
    Text(
        text = stringResource(R.string.ui_video_chip_live),
        color = colors.onLive,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.live)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
