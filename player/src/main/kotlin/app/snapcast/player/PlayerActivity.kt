package app.snapcast.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.snapcast.data.di.ApplicationScope
import app.snapcast.data.playback.PlaybackPositionRepository
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.player.media.PlayerFactory
import app.snapcast.player.pip.PictureInPictureController
import app.snapcast.player.resume.PositionWriter
import app.snapcast.player.ui.ChromeState
import app.snapcast.player.ui.PlayerScreen
import app.snapcast.player.ui.PlayerScreenActions
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

/**
 * Full-screen Media3 player for a [PlaybackRequest] (HLS, DASH or progressive) that replays the
 * browser's request headers, side-loads subtitles, resumes where the user left off and supports
 * picture-in-picture. Started through [PlayerLauncher]; DRM-protected videos are filtered out by the UI.
 */
@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var positionRepository: PlaybackPositionRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    @field:ApplicationScope
    lateinit var applicationScope: CoroutineScope

    // Null when the activity finished in onCreate because the launch intent had no usable request.
    private var playback: PlaybackController? = null
    private var positionWriter: PositionWriter? = null
    private var pictureInPicture: PictureInPictureController? = null
    private var playerView: PlayerView? = null
    private val chrome = MutableStateFlow(ChromeState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PlaybackRequestCodec.decode(intent?.getStringExtra(EXTRA_REQUEST))
        if (request == null) {
            Toast.makeText(this, R.string.player_error_missing_request, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setUpWindow()

        val writer = PositionWriter(positionRepository, applicationScope) { error ->
            Log.w(TAG, "Couldn't store the playback position", error)
        }
        val controller = PlaybackController(
            context = this,
            playerFactory = PlayerFactory(okHttpClient),
            positionRepository = positionRepository,
            positionWriter = writer,
            resumeEnabled = { settingsRepository.settings.value.resumePlayback },
            scope = lifecycleScope,
        )
        val pip = PictureInPictureController(this, onPlay = controller::play, onPause = controller::pause)
        controller.onPlaybackChanged = ::updatePictureInPictureParams
        pip.register()
        positionWriter = writer
        playback = controller
        pictureInPicture = pip

        val landscapeLocked = savedInstanceState?.getBoolean(STATE_LANDSCAPE_LOCKED) ?: false
        if (landscapeLocked) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        chrome.value = ChromeState(
            isInPictureInPicture = isInPictureInPictureMode,
            canEnterPictureInPicture = pip.isSupported,
            landscapeLocked = landscapeLocked,
        )
        controller.open(
            request = request,
            explicitStartMs = savedInstanceState?.restoredPositionMs() ?: request.startPositionMs,
            playWhenReady = savedInstanceState?.getBoolean(STATE_PLAY_WHEN_READY, true) ?: true,
        )

        setContent {
            val player by controller.player.collectAsStateWithLifecycle()
            val playbackState by controller.state.collectAsStateWithLifecycle()
            val chromeState by chrome.collectAsStateWithLifecycle()
            PlayerScreen(player = player, playback = playbackState, chrome = chromeState, actions = screenActions)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val controller = playback ?: return
        val request = PlaybackRequestCodec.decode(intent.getStringExtra(EXTRA_REQUEST))
        if (request == null) {
            Toast.makeText(this, R.string.player_error_missing_request, Toast.LENGTH_LONG).show()
            return
        }
        setIntent(intent)
        if (request == controller.request) {
            // Same video chosen again: keep the current position.
            controller.play()
            return
        }
        controller.open(request, explicitStartMs = request.startPositionMs, playWhenReady = true)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 31+ enters automatically through PictureInPictureParams.setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && playback?.state?.value?.isPlaying == true) {
            enterPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        chrome.update { it.copy(isInPictureInPicture = isInPictureInPictureMode) }
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            // The PiP window was dismissed (the activity is already stopped): stop instead of lingering.
            playback?.pause()
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        val controller = playback ?: return
        controller.savePosition()
        if (!isInPictureInPictureMode && !isChangingConfigurations) controller.pause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val player = playback?.player?.value ?: return
        if (player.currentMediaItem != null && !player.isCurrentMediaItemLive) {
            outState.putLong(STATE_POSITION, player.currentPosition)
        }
        outState.putBoolean(STATE_PLAY_WHEN_READY, player.playWhenReady)
        outState.putBoolean(STATE_LANDSCAPE_LOCKED, chrome.value.landscapeLocked)
    }

    override fun onDestroy() {
        playback?.release()
        pictureInPicture?.unregister()
        positionWriter?.close()
        playerView = null
        super.onDestroy()
    }

    private val screenActions = object : PlayerScreenActions {
        @OptIn(UnstableApi::class)
        override fun onPlayerViewCreated(view: PlayerView) {
            playerView = view
            // The surface is resized to the video's aspect ratio; keep the PiP source rect in sync with it.
            view.videoSurfaceView?.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    updatePictureInPictureParams()
                }
            }
        }

        override fun onControllerVisibilityChanged(visible: Boolean) {
            chrome.update { it.copy(controllerVisible = visible) }
        }

        override fun onFullscreenToggled(fullscreen: Boolean) {
            requestedOrientation = if (fullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            chrome.update { it.copy(landscapeLocked = fullscreen) }
        }

        override fun onBack() {
            onBackPressedDispatcher.onBackPressed()
        }

        override fun onEnterPictureInPicture() {
            enterPictureInPicture()
        }

        override fun onStartOver() {
            playback?.startOver()
        }

        override fun onDismissResumeNotice() {
            playback?.dismissResumeNotice()
        }

        override fun onRetry() {
            playback?.retry()
        }
    }

    @OptIn(UnstableApi::class)
    private fun enterPictureInPicture() {
        val controller = playback ?: return
        val state = controller.state.value
        pictureInPicture?.enter(controller.player.value, state.isPlaying && state.error == null, playerView?.videoSurfaceView)
    }

    @OptIn(UnstableApi::class)
    private fun updatePictureInPictureParams() {
        val controller = playback ?: return
        val state = controller.state.value
        pictureInPicture?.update(controller.player.value, state.isPlaying && state.error == null, playerView?.videoSurfaceView)
    }

    /** Edge-to-edge black window; system bars hidden, revealed transiently by a swipe. */
    private fun setUpWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun Bundle.restoredPositionMs(): Long? =
        if (containsKey(STATE_POSITION)) getLong(STATE_POSITION) else null

    companion object {
        private const val TAG = "SnapcastPlayer"
        private const val EXTRA_REQUEST = "app.snapcast.player.extra.REQUEST"
        private const val STATE_POSITION = "position_ms"
        private const val STATE_PLAY_WHEN_READY = "play_when_ready"
        private const val STATE_LANDSCAPE_LOCKED = "landscape_locked"

        /** Intent that plays [request]; the request travels as JSON. */
        internal fun intent(context: Context, request: PlaybackRequest): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_REQUEST, PlaybackRequestCodec.encode(request))
    }
}
