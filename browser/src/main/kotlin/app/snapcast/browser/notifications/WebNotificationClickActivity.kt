package app.snapcast.browser.notifications

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dagger.hilt.android.EntryPointAccessors

/**
 * Invisible activity behind a web notification tap: fires the page's click handler, brings the
 * app's task to the front and finishes immediately (an activity, because Android 12+ forbids
 * starting activities from notification receivers).
 */
class WebNotificationClickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = intent?.getStringExtra(EXTRA_TAG)
        if (tag != null) {
            EntryPointAccessors.fromApplication(applicationContext, WebNotificationEntryPoint::class.java)
                .webNotifications()
                .onClicked(tag)
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        finish()
    }

    internal companion object {
        private const val EXTRA_TAG = "web_notification_tag"

        fun intent(context: Context, tag: String): Intent =
            Intent(context, WebNotificationClickActivity::class.java)
                .putExtra(EXTRA_TAG, tag)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
