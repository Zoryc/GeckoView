package app.snapcast.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.snapcast.cast.CastErrorKind
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.ui.R
import app.snapcast.ui.theme.SnapcastThemeExtras

/** Explains that DRM-protected videos are never cast or played. */
@Composable
fun DrmDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.GppBad, contentDescription = null, tint = SnapcastThemeExtras.colors.drm) },
        title = { Text(stringResource(R.string.ui_video_drm_title)) },
        text = { Text(stringResource(R.string.ui_video_drm_message)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_video_dialog_ok)) } },
    )
}

/** A cast failure. Wi-Fi problems get an explanation of the same-network requirement. */
@Composable
fun CastErrorDialog(kind: CastErrorKind, message: String, onDismiss: () -> Unit) {
    if (kind == CastErrorKind.DRM) {
        DrmDialog(onDismiss)
        return
    }
    val (titleRes, bodyRes) = when (kind) {
        CastErrorKind.NO_WIFI -> R.string.ui_video_error_wifi_title to R.string.ui_video_error_wifi_message
        CastErrorKind.UNAVAILABLE -> R.string.ui_video_error_title to R.string.ui_video_error_unavailable
        CastErrorKind.PROXY_FAILED -> R.string.ui_video_error_title to R.string.ui_video_error_proxy
        CastErrorKind.UNSUPPORTED -> R.string.ui_video_error_title to R.string.ui_video_error_unsupported
        CastErrorKind.LOAD_FAILED, CastErrorKind.DRM -> R.string.ui_video_error_title to null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (kind == CastErrorKind.NO_WIFI) Icons.Filled.WifiOff else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                if (bodyRes != null) {
                    Text(stringResource(bodyRes))
                }
                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = if (bodyRes != null) 12.dp else 0.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_video_dialog_ok)) } },
    )
}

/**
 * Picks the quality (HLS variant) Cast, Play on phone and the queue use for [video]; null is "Automatic" (the
 * player or receiver adapts to the connection and screen). [picked] is checked.
 */
@Composable
fun QualityDialog(video: DetectedVideo, picked: VideoVariant?, onPick: (VideoVariant?) -> Unit, onDismiss: () -> Unit) {
    val choices = VideoLabels.qualityChoices(video)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_video_quality_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                item {
                    ChoiceRow(
                        title = stringResource(R.string.ui_video_quality_auto),
                        subtitle = video.qualityLabel?.let { stringResource(R.string.ui_video_quality_auto_details, it) },
                        selected = picked == null,
                        onClick = { onPick(null) },
                    )
                }
                items(choices) { variant ->
                    ChoiceRow(
                        title = variant.qualityLabel,
                        subtitle = VideoLabels.variantDetails(variant),
                        selected = variant.url == picked?.url,
                        onClick = { onPick(variant) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_video_dialog_cancel)) } },
    )
}

/** Picks the subtitle track to enable when casting. */
@Composable
fun SubtitleDialog(video: DetectedVideo, onPick: (SubtitleTrack) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_video_subtitles_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(video.subtitles.size) { index ->
                    val track = video.subtitles[index]
                    ChoiceRow(
                        title = subtitleLabel(track, index),
                        subtitle = listOfNotNull(track.language, track.format.name.takeIf { it != "UNKNOWN" })
                            .joinToString(" · ")
                            .ifBlank { null },
                        onClick = { onPick(track) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_video_dialog_cancel)) } },
    )
}

@Composable
private fun subtitleLabel(track: SubtitleTrack, index: Int): String =
    track.label?.takeIf { it.isNotBlank() }
        ?: track.language?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.ui_video_subtitle_track, index + 1)

@Composable
private fun ChoiceRow(title: String, subtitle: String?, onClick: () -> Unit, selected: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
