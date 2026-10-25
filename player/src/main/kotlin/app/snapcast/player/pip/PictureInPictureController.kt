package app.snapcast.player.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.View
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import app.snapcast.player.R

/**
 * Picture-in-picture for the player activity: keeps [PictureInPictureParams] (aspect ratio from the video,
 * source rect for the enter animation, a play/pause [RemoteAction], auto-enter on API 31+) up to date and
 * routes the action's broadcasts back to [onPlay] / [onPause].
 */
internal class PictureInPictureController(
    private val activity: Activity,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
) {
    val isSupported: Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CONTROL) return
            when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                CONTROL_PLAY -> onPlay()
                CONTROL_PAUSE -> onPause()
            }
        }
    }

    /** Starts listening for the PiP window's play/pause action (only this app can send it). */
    fun register() {
        if (!isSupported || receiverRegistered) return
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(ACTION_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        activity.unregisterReceiver(receiver)
        receiverRegistered = false
    }

    /**
     * Pushes fresh parameters to the system. [isPlaying] decides the action shown and, on API 31+, whether
     * leaving the app enters PiP automatically. [videoSurface] is the view showing the video.
     */
    fun update(player: Player?, isPlaying: Boolean, videoSurface: View?) {
        if (!isSupported) return
        try {
            activity.setPictureInPictureParams(buildParams(player, isPlaying, videoSurface))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Rejected picture-in-picture parameters", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Picture-in-picture unavailable", e)
        }
    }

    /** Enters PiP now; false when the device or the user's settings don't allow it. */
    fun enter(player: Player?, isPlaying: Boolean, videoSurface: View?): Boolean {
        if (!isSupported) return false
        return try {
            activity.enterPictureInPictureMode(buildParams(player, isPlaying, videoSurface))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Rejected picture-in-picture parameters", e)
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Picture-in-picture unavailable", e)
            false
        }
    }

    private fun buildParams(player: Player?, isPlaying: Boolean, videoSurface: View?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val videoSize = player?.videoSize
        val ratio = videoSize?.let { PipAspectRatio.of(it.width, it.height, it.pixelWidthHeightRatio) }
        if (ratio != null) builder.setAspectRatio(Rational(ratio.numerator, ratio.denominator))
        visibleRect(videoSurface)?.let { builder.setSourceRectHint(it) }
        builder.setActions(listOf(playPauseAction(isPlaying)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(isPlaying)
        return builder.build()
    }

    private fun visibleRect(view: View?): Rect? {
        if (view == null || !view.isAttachedToWindow) return null
        val rect = Rect()
        return rect.takeIf { view.getGlobalVisibleRect(it) && !it.isEmpty }
    }

    private fun playPauseAction(isPlaying: Boolean): RemoteAction {
        val control = if (isPlaying) CONTROL_PAUSE else CONTROL_PLAY
        val icon = if (isPlaying) R.drawable.player_ic_pause else R.drawable.player_ic_play
        val title = activity.getString(if (isPlaying) R.string.player_pause else R.string.player_play)
        val intent = Intent(ACTION_CONTROL)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_CONTROL, control)
        // One request code per control: extras alone don't make PendingIntents distinct.
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            control,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(activity, icon), title, title, pendingIntent)
    }

    private companion object {
        const val TAG = "SnapcastPlayer"
        const val ACTION_CONTROL = "app.snapcast.player.action.PIP_CONTROL"
        const val EXTRA_CONTROL = "app.snapcast.player.extra.PIP_CONTROL"
        const val CONTROL_PLAY = 1
        const val CONTROL_PAUSE = 2
    }
}
