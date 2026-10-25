package app.snapcast.browser.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import app.snapcast.browser.R
import app.snapcast.browser.web.UriParts
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate

/**
 * Shows web notifications (sites the user allowed to notify) as Android notifications. Tapping one
 * fires the page's `click` handler and brings the app to the front; swiping it away fires `close`.
 * Nothing is shown when the app may not post notifications.
 */
@Singleton
internal class WebNotificationController @Inject constructor(
    @ApplicationContext private val context: Context,
) : WebNotificationDelegate {
    private class Shown(val id: Int, val notification: WebNotification)

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shown = HashMap<String, Shown>()
    private val ids = AtomicInteger(FIRST_NOTIFICATION_ID)

    override fun onShowNotification(notification: WebNotification) {
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val id = synchronized(shown) {
            val reused = shown[notification.tag]?.id ?: ids.getAndIncrement()
            shown[notification.tag] = Shown(reused, notification)
            reused
        }
        val site = UriParts.hostOf(notification.origin) ?: notification.origin
        val text = notification.text.orEmpty()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notification.title?.takeIf { it.isNotBlank() } ?: site)
            .setContentText(text)
            .setSubText(site)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setSilent(notification.silent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (notification.privateBrowsing) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent(WebNotificationClickActivity.intent(context, notification.tag), id, activity = true))
            .setDeleteIntent(pendingIntent(WebNotificationDismissReceiver.intent(context, notification.tag), id, activity = false))
        manager.notify(id, builder.build())
        mainHandler.post { notification.show() }
    }

    override fun onCloseNotification(notification: WebNotification) {
        val entry = synchronized(shown) { shown.remove(notification.tag) } ?: return
        manager.cancel(entry.id)
    }

    /** The user tapped the notification with [tag]. Main thread. */
    fun onClicked(tag: String) {
        synchronized(shown) { shown.remove(tag) }?.notification?.click()
    }

    /** The user swiped the notification with [tag] away. Main thread. */
    fun onDismissed(tag: String) {
        synchronized(shown) { shown.remove(tag) }?.notification?.dismiss()
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.browser_web_notifications_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.browser_web_notifications_channel_description) }
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntent(intent: Intent, requestCode: Int, activity: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (activity) {
            PendingIntent.getActivity(context, requestCode, intent, flags)
        } else {
            PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }
    }

    private companion object {
        const val CHANNEL_ID = "browser_web_notifications"
        const val FIRST_NOTIFICATION_ID = 0x5E0000
    }
}
