package app.snapcast.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Starts [PlayerActivity]; adds [Intent.FLAG_ACTIVITY_NEW_TASK] when [Context] is not (backed by) an Activity.
 * Also carries the player's "reload the page" requests back to the browser, in memory (same process).
 */
@Singleton
internal class PlayerLauncherImpl @Inject constructor() : PlayerLauncher {
    private val reloads = Channel<PlaybackRequest>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val reloadRequests: Flow<PlaybackRequest> = reloads.receiveAsFlow()

    /** Called by the player for a refused link: the browser reloads [request]'s page and plays it again. */
    fun requestReload(request: PlaybackRequest) {
        reloads.trySend(request)
    }

    override fun play(context: Context, request: PlaybackRequest) {
        val intent = PlayerActivity.intent(context, request)
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}
