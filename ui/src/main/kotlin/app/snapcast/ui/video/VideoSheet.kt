package app.snapcast.ui.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.util.UrlUtils
import app.snapcast.ui.R
import app.snapcast.ui.components.LiveTag
import app.snapcast.ui.theme.SnapcastThemeExtras
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FEEDBACK_MS = 1_500L

/**
 * Bottom sheet listing the videos detected on the current page with their actions. Actions that leave
 * the page (cast, play, open with) close the sheet first; copy / queue confirm inline and keep it open.
 * While it is open, videos without a usable picture get a frame captured in the page ([onNeedThumbnails]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSheet(
    videos: List<DetectedVideo>,
    isDetectorReady: Boolean,
    actions: VideoActions,
    onRefresh: () -> Unit,
    onNeedThumbnails: () -> Unit,
    onPosterFailed: (DetectedVideo, Throwable) -> Unit,
    onDismiss: () -> Unit,
    /** Posters of this page that already failed: not requested again. */
    failedPosters: Set<String> = emptySet(),
    /** Opens "Report a site problem"; null hides the link (private tabs). */
    onReportProblem: (() -> Unit)? = null,
) {
    // Asked again whenever a video without a usable poster or a frame shows up, and on each opening; the page
    // draws each video once per list.
    val missingPictures = videos.filter { (it.posterUrl == null || it.posterUrl in failedPosters) && it.frameThumbnail == null }.map { it.id }
    LaunchedEffect(missingPictures) {
        if (missingPictures.isNotEmpty()) onNeedThumbnails()
    }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val closeThen: (() -> Unit) -> Unit = { block ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            block()
        }
    }
    val sheetActions = remember(actions) {
        VideoActions(
            cast = { video, variant, subtitle, direct -> closeThen { actions.cast(video, variant, subtitle, direct) } },
            playOnPhone = { video, variant -> closeThen { actions.playOnPhone(video, variant) } },
            copyLink = actions.copyLink,
            openWithOtherApp = { video -> closeThen { actions.openWithOtherApp(video) } },
            addToQueue = actions.addToQueue,
            explainDrm = actions.explainDrm,
            canCastDirectly = actions.canCastDirectly,
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.animateContentSize()) {
            SheetHeader(count = videos.size, onRefresh = onRefresh)
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            AnimatedContent(
                targetState = videos.isEmpty(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "videoSheetContent",
                // A long list scrolls instead of pushing the report link off the sheet.
                modifier = Modifier.weight(1f, fill = false),
            ) { empty ->
                if (empty) {
                    EmptyVideos(isDetectorReady = isDetectorReady, onRefresh = onRefresh)
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = if (onReportProblem != null) 0.dp else 16.dp)) {
                        items(videos, key = { it.id }) { video ->
                            VideoItem(
                                video = video,
                                actions = sheetActions,
                                posterFailed = video.posterUrl in failedPosters,
                                onPosterFailed = { error -> onPosterFailed(video, error) },
                                modifier = Modifier.animateItem(),
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
            if (onReportProblem != null) {
                TextButton(
                    onClick = { closeThen(onReportProblem) },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.ui_video_report_problem), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(count: Int, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.ui_video_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.ui_video_sheet_count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.ui_video_refresh))
        }
    }
}

@Composable
private fun EmptyVideos(isDetectorReady: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isDetectorReady) {
            Icon(
                imageVector = Icons.Filled.TravelExplore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
        }
        Text(
            text = stringResource(if (isDetectorReady) R.string.ui_video_empty_title else R.string.ui_video_detector_starting),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.ui_video_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ui_video_rescan))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoItem(
    video: DetectedVideo,
    actions: VideoActions,
    posterFailed: Boolean,
    onPosterFailed: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drm = video.isDrmProtected
    // The quality picked in "Choose quality" (by variant URL); Cast, Play, Queue and Subtitles all use it.
    var pickedUrl by rememberSaveable(video.id) { mutableStateOf<String?>(null) }
    val picked: VideoVariant? = pickedUrl?.let { url -> VideoLabels.qualityChoices(video).firstOrNull { it.url == url } }
    var showMenu by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(FEEDBACK_MS)
            copied = false
        }
    }
    LaunchedEffect(queued) {
        if (queued) {
            delay(FEEDBACK_MS)
            queued = false
        }
    }
    val explain = { actions.explainDrm(video) }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row {
            VideoThumbnail(video, posterFailed, onPosterFailed)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    VideoLabels.chips(video, picked).forEach { chip -> VideoChipTag(chip) }
                }
            }
        }
        if (drm) {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = explain)
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.GppBad,
                    contentDescription = null,
                    tint = SnapcastThemeExtras.colors.drm,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.ui_video_drm_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = SnapcastThemeExtras.colors.drm,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrmGuard(blocked = drm, onBlocked = explain) {
                Button(
                    onClick = { actions.cast(video, picked, null, false) },
                    enabled = !drm,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_video_cast))
                }
            }
            Spacer(Modifier.width(8.dp))
            DrmGuard(blocked = drm, onBlocked = explain) {
                FilledTonalIconButton(onClick = { actions.playOnPhone(video, picked) }, enabled = !drm) {
                    Icon(Icons.Filled.PhoneAndroid, contentDescription = stringResource(R.string.ui_video_play))
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                actions.copyLink(video)
                copied = true
            }) {
                Icon(
                    imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = stringResource(if (copied) R.string.ui_video_copied else R.string.ui_video_copy),
                )
            }
            DrmGuard(blocked = drm, onBlocked = explain) {
                IconButton(onClick = { actions.openWithOtherApp(video) }, enabled = !drm) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.ui_video_open_with))
                }
            }
            DrmGuard(blocked = drm, onBlocked = explain) {
                IconButton(
                    onClick = {
                        actions.addToQueue(video, picked)
                        queued = true
                    },
                    enabled = !drm,
                ) {
                    Icon(
                        imageVector = if (queued) {
                            Icons.AutoMirrored.Filled.PlaylistAddCheck
                        } else {
                            Icons.AutoMirrored.Filled.PlaylistAdd
                        },
                        contentDescription = stringResource(if (queued) R.string.ui_video_queued else R.string.ui_video_queue),
                    )
                }
            }
            DrmGuard(blocked = drm, onBlocked = explain) {
                Box {
                    IconButton(onClick = { showMenu = true }, enabled = !drm) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ui_video_more))
                    }
                    VideoOverflowMenu(
                        expanded = showMenu,
                        video = video,
                        canCastDirectly = actions.canCastDirectly(video),
                        onDismiss = { showMenu = false },
                        onCastDirect = {
                            showMenu = false
                            actions.cast(video, picked, null, true)
                        },
                        onChooseQuality = {
                            showMenu = false
                            showQuality = true
                        },
                        onChooseSubtitles = {
                            showMenu = false
                            showSubtitles = true
                        },
                    )
                }
            }
        }
    }

    if (showQuality) {
        QualityDialog(
            video = video,
            picked = picked,
            onPick = { variant ->
                showQuality = false
                pickedUrl = variant?.url
            },
            onDismiss = { showQuality = false },
        )
    }
    if (showSubtitles) {
        SubtitleDialog(
            video = video,
            onPick = { track ->
                showSubtitles = false
                actions.cast(video, picked, track, false)
            },
            onDismiss = { showSubtitles = false },
        )
    }
}

@Composable
private fun VideoOverflowMenu(
    expanded: Boolean,
    video: DetectedVideo,
    canCastDirectly: Boolean,
    onDismiss: () -> Unit,
    onCastDirect: () -> Unit,
    onChooseQuality: () -> Unit,
    onChooseSubtitles: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.ui_video_cast_direct))
                    if (!canCastDirectly) {
                        Text(
                            text = stringResource(R.string.ui_video_cast_direct_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            leadingIcon = { Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null) },
            onClick = onCastDirect,
        )
        if (VideoLabels.qualityChoices(video).size > 1) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ui_video_choose_quality)) },
                leadingIcon = { Icon(Icons.Filled.HighQuality, contentDescription = null) },
                onClick = onChooseQuality,
            )
        }
        if (video.subtitles.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ui_video_subtitles)) },
                leadingIcon = { Icon(Icons.Filled.Subtitles, contentDescription = null) },
                onClick = onChooseSubtitles,
            )
        }
    }
}

/**
 * Wraps an action of a DRM-protected video: the action is rendered disabled and a tap on it explains
 * why instead of doing nothing.
 */
@Composable
private fun DrmGuard(blocked: Boolean, onBlocked: () -> Unit, content: @Composable () -> Unit) {
    Box {
        Box(modifier = if (blocked) Modifier.alpha(0.6f) else Modifier) { content() }
        if (blocked) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onBlocked),
            )
        }
    }
}

private fun StreamType.placeholderIcon(isLive: Boolean): ImageVector = when {
    isLive -> Icons.Filled.LiveTv
    this == StreamType.HLS || this == StreamType.DASH -> Icons.Filled.Stream
    this == StreamType.OTHER -> Icons.Filled.OndemandVideo
    else -> Icons.Filled.Movie
}

/**
 * Poster request headers, as the browser sent them for that image: the Referer of the document that showed it
 * ([DetectedVideo.posterReferer]: the player's frame, or the page for og:image; never the video's own Referer,
 * which hotlink protection refuses for an image of the page), the User-Agent, and cookies only for the
 * poster's own host.
 */
private fun posterHeaders(video: DetectedVideo, posterUrl: String): NetworkHeaders {
    val builder = NetworkHeaders.Builder()
    video.posterReferer?.takeIf { it.isNotBlank() }?.let { builder.set("Referer", it) }
    video.headers.userAgent?.takeIf { it.isNotBlank() }?.let { builder.set("User-Agent", it) }
    val posterHost = UrlUtils.hostOf(posterUrl)
    val cookie = posterHost?.let { host ->
        video.cookiesByHost[host] ?: video.headers.cookie.takeIf { host == UrlUtils.hostOf(video.url) }
    }
    cookie?.takeIf { it.isNotBlank() }?.let { builder.set("Cookie", it) }
    return builder.build()
}

/**
 * The video's poster, else (or when the poster cannot be loaded: [posterFailed], or it fails now) the frame
 * captured in the page, else the type icon. A failure is reported once ([onPosterFailed], which asks for a frame).
 */
@Composable
private fun VideoThumbnail(video: DetectedVideo, posterFailed: Boolean, onPosterFailed: (Throwable) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 120.dp, height = 68.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = video.type.placeholderIcon(video.isLive),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        val poster = video.posterUrl?.takeIf { UrlUtils.isHttp(it) && !posterFailed }
        val frame = video.frameThumbnail
        val context = LocalContext.current
        if (poster != null) {
            val request = remember(poster, video.posterReferer, video.headers.userAgent, video.cookiesByHost, video.isPrivate) {
                ImageRequest.Builder(context)
                    .data(poster)
                    .httpHeaders(posterHeaders(video, poster))
                    // A private tab's poster stays in memory only, so nothing of it is left on disk.
                    .diskCachePolicy(if (video.isPrivate) CachePolicy.DISABLED else CachePolicy.ENABLED)
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { error -> onPosterFailed(error.result.throwable) },
                modifier = Modifier.matchParentSize(),
            )
        } else if (frame != null) {
            // A small JPEG data URL the page captured; never cached on disk (it may come from a private tab).
            val request = remember(frame) {
                ImageRequest.Builder(context)
                    .data(frame)
                    .memoryCacheKey("frame:${video.id}")
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (video.isLive) {
            LiveTag(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun VideoChipTag(chip: VideoChip) {
    val extras = SnapcastThemeExtras.colors
    val (container, content) = when (chip.kind) {
        VideoChipKind.TYPE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        VideoChipKind.QUALITY, VideoChipKind.QUALITY_RANGE ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        VideoChipKind.LIVE -> extras.live to extras.onLive
        VideoChipKind.DRM -> extras.drm to extras.onDrm
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        chip.kind == VideoChipKind.QUALITY_RANGE -> stringResource(R.string.ui_video_quality_up_to, chip.text.orEmpty())
        chip.text != null -> chip.text
        chip.kind == VideoChipKind.LIVE -> stringResource(R.string.ui_video_chip_live)
        chip.kind == VideoChipKind.DRM -> stringResource(R.string.ui_video_chip_drm)
        chip.kind == VideoChipKind.SUBTITLES -> stringResource(R.string.ui_video_chip_cc)
        else -> ""
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (chip.kind == VideoChipKind.DOMAIN) FontWeight.Normal else FontWeight.SemiBold,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
