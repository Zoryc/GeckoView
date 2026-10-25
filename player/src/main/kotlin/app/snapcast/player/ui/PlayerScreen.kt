package app.snapcast.player.ui

import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.snapcast.core.util.Formatters
import app.snapcast.player.PlaybackUiState
import app.snapcast.player.R
import app.snapcast.player.error.PlayerErrorUi
import kotlinx.coroutines.delay

/** Window-level state owned by the activity (as opposed to [PlaybackUiState]). */
internal data class ChromeState(
    /** Mirrors the PlayerView controller so the top bar shows and hides with it. */
    val controllerVisible: Boolean = false,
    val isInPictureInPicture: Boolean = false,
    val canEnterPictureInPicture: Boolean = false,
    /** Set by the controller's fullscreen button: the activity is locked to landscape. */
    val landscapeLocked: Boolean = false,
)

/** Callbacks from the player screen to the activity. */
internal interface PlayerScreenActions {
    fun onPlayerViewCreated(view: PlayerView)
    fun onControllerVisibilityChanged(visible: Boolean)
    fun onFullscreenToggled(fullscreen: Boolean)
    fun onBack()
    fun onEnterPictureInPicture()
    fun onStartOver()
    fun onDismissResumeNotice()
    fun onRetry()
}

/**
 * Full-screen player: Media3's [PlayerView] (controls, speed and track menus, subtitles) with Compose
 * overlays for the title bar, the resume notice and errors. Overlays are hidden in picture-in-picture.
 */
@Composable
internal fun PlayerScreen(
    player: Player?,
    playback: PlaybackUiState,
    chrome: ChromeState,
    actions: PlayerScreenActions,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                PlayerSurface(
                    player = player,
                    keepScreenOn = playback.isPlaying,
                    chrome = chrome,
                    actions = actions,
                    modifier = Modifier
                        .fillMaxSize()
                        // Cutouts are stable, unlike the transient system bars, so the video never jumps.
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
                )
                val overlaysAllowed = !chrome.isInPictureInPicture && playback.error == null
                AnimatedVisibility(
                    visible = overlaysAllowed && chrome.controllerVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                ) {
                    TopBar(
                        title = playback.title,
                        showPictureInPicture = chrome.canEnterPictureInPicture,
                        onBack = actions::onBack,
                        onEnterPictureInPicture = actions::onEnterPictureInPicture,
                    )
                }
                ResumeNotice(
                    resumedAtMs = playback.resumedAtMs.takeIf { overlaysAllowed },
                    onStartOver = actions::onStartOver,
                    onDismiss = actions::onDismissResumeNotice,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .padding(top = 64.dp, start = 16.dp, end = 16.dp),
                )
                playback.error?.let { error ->
                    ErrorOverlay(
                        error = error,
                        compact = chrome.isInPictureInPicture,
                        onRetry = actions::onRetry,
                        onClose = actions::onBack,
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    player: Player?,
    keepScreenOn: Boolean,
    chrome: ChromeState,
    actions: PlayerScreenActions,
    modifier: Modifier,
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setShowSubtitleButton(true)
                setShowNextButton(false)
                setShowPreviousButton(false)
                setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { visibility ->
                        actions.onControllerVisibilityChanged(visibility == View.VISIBLE)
                    },
                )
                setFullscreenButtonClickListener(
                    PlayerView.FullscreenButtonClickListener { fullscreen -> actions.onFullscreenToggled(fullscreen) },
                )
                actions.onPlayerViewCreated(this)
            }
        },
        modifier = modifier,
        onRelease = { view -> view.player = null },
        update = { view ->
            view.player = player
            view.useController = !chrome.isInPictureInPicture
            view.keepScreenOn = keepScreenOn
            view.setFullscreenButtonState(chrome.landscapeLocked)
        },
    )
}

@Composable
private fun TopBar(
    title: String,
    showPictureInPicture: Boolean,
    onBack: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.player_back))
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showPictureInPicture) {
            IconButton(onClick = onEnterPictureInPicture) {
                Icon(Icons.Filled.PictureInPictureAlt, contentDescription = stringResource(R.string.player_enter_pip))
            }
        }
    }
}

/** "Resumed at 12:34 · Start over" chip; dismisses itself after a few seconds. */
@Composable
private fun ResumeNotice(
    resumedAtMs: Long?,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    // Keeps the text while the chip fades out after resumedAtMs became null.
    var shownPositionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(resumedAtMs) {
        if (resumedAtMs != null) {
            shownPositionMs = resumedAtMs
            delay(RESUME_NOTICE_DURATION_MS)
            onDismiss()
        }
    }
    AnimatedVisibility(visible = resumedAtMs != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = Color(0xE6202124),
            contentColor = Color.White,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_resumed_at, Formatters.durationMs(resumedAtMs ?: shownPositionMs)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onStartOver) {
                    Text(stringResource(R.string.player_start_over))
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.player_dismiss))
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    error: PlayerErrorUi,
    compact: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            // Swallows taps so they don't toggle the player controls underneath.
            .clickable(interactionSource = interactionSource, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        if (compact) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = error.message, modifier = Modifier.size(32.dp))
        } else {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp))
                Text(
                    text = stringResource(R.string.player_error_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(text = error.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text(
                    text = error.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onClose) {
                        Text(stringResource(R.string.player_close))
                    }
                    if (error.canRetry) {
                        Button(onClick = onRetry) {
                            Text(stringResource(R.string.player_retry))
                        }
                    }
                }
            }
        }
    }
}

private const val RESUME_NOTICE_DURATION_MS = 8_000L
