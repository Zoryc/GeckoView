package app.snapcast.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snapcast.browser.BrowserEvent
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.util.UrlUtils
import app.snapcast.ui.R
import kotlinx.coroutines.launch

/** Actions of the long-press menu. URLs are passed explicitly so one callback serves links and media. */
internal class ContextMenuActions(
    val openInNewTab: (url: String, private: Boolean) -> Unit,
    val copy: (url: String) -> Unit,
    val share: (url: String, title: String?) -> Unit,
    val castVideo: (DetectedVideo) -> Unit,
    val playVideo: (DetectedVideo) -> Unit,
)

/**
 * Long-press menu for links, images and videos. For `<video>` elements with a castable source the
 * video can be cast or played directly (using the detected entry when there is one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextMenuSheet(
    state: ContextMenuState,
    isPrivateTab: Boolean,
    actions: ContextMenuActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val closeThen: (() -> Unit) -> () -> Unit = { block ->
        {
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                onDismiss()
                block()
            }
        }
    }
    val event = state.event
    val link = event.linkUrl?.takeIf { it.isNotBlank() }
    val src = event.srcUrl?.takeIf { it.isNotBlank() }
    val header = link ?: src

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (header != null) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    val title = event.title?.takeIf { it.isNotBlank() } ?: event.altText?.takeIf { it.isNotBlank() }
                    if (title != null) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        text = header,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
            if (link != null && UrlUtils.isHttp(link)) {
                if (!isPrivateTab) {
                    MenuRow(Icons.AutoMirrored.Filled.OpenInNew, R.string.ui_ctx_open_new_tab, closeThen {
                        actions.openInNewTab(link, false)
                    })
                }
                MenuRow(Icons.Filled.TheaterComedy, R.string.ui_ctx_open_private_tab, closeThen {
                    actions.openInNewTab(link, true)
                })
            }
            if (link != null) {
                MenuRow(Icons.Filled.Link, R.string.ui_ctx_copy_link, closeThen { actions.copy(link) })
                MenuRow(Icons.Filled.Share, R.string.ui_ctx_share_link, closeThen { actions.share(link, event.title) })
            }
            if (event.type == BrowserEvent.ContextElementType.IMAGE && src != null) {
                if (UrlUtils.isHttp(src)) {
                    MenuRow(Icons.Filled.Image, R.string.ui_ctx_open_image, closeThen {
                        actions.openInNewTab(src, isPrivateTab)
                    })
                }
                MenuRow(Icons.Filled.ContentCopy, R.string.ui_ctx_copy_image_link, closeThen { actions.copy(src) })
            }
            if (event.type == BrowserEvent.ContextElementType.VIDEO) {
                val video = state.video
                if (video != null) {
                    MenuRow(Icons.Filled.Cast, R.string.ui_ctx_cast_video, closeThen { actions.castVideo(video) })
                    MenuRow(Icons.Filled.PhoneAndroid, R.string.ui_ctx_play_video, closeThen { actions.playVideo(video) })
                }
                val videoLink = video?.url ?: src
                if (videoLink != null) {
                    MenuRow(Icons.Filled.VideoFile, R.string.ui_ctx_copy_video_link, closeThen { actions.copy(videoLink) })
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
