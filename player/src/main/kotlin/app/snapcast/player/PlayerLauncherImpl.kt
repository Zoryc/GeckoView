package app.snapcast.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import javax.inject.Inject

/** Starts [PlayerActivity]; adds [Intent.FLAG_ACTIVITY_NEW_TASK] when [Context] is not (backed by) an Activity. */
internal class PlayerLauncherImpl @Inject constructor() : PlayerLauncher {

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
