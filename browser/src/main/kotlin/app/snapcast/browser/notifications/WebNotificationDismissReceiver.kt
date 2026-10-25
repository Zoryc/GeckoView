package app.snapcast.browser.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors

/** Swiping a web notification away fires the page's `close` event. */
class WebNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        EntryPointAccessors.fromApplication(context.applicationContext, WebNotificationEntryPoint::class.java)
            .webNotifications()
            .onDismissed(tag)
    }

    internal companion object {
        private const val EXTRA_TAG = "web_notification_tag"

        fun intent(context: Context, tag: String): Intent =
            Intent(context, WebNotificationDismissReceiver::class.java).putExtra(EXTRA_TAG, tag)
    }
}
