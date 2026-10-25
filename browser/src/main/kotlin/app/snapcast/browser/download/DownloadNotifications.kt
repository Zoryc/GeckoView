package app.snapcast.browser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.snapcast.browser.R
import app.snapcast.core.util.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Download notifications on the low-importance "browser_downloads" channel. Posting goes through
 * the framework [NotificationManager], which silently drops notifications when POST_NOTIFICATIONS
 * is not granted, so downloads keep working without the permission.
 */
@Singleton
internal class DownloadNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.browser_download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.browser_download_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Ongoing notification of the foreground service; [request] null while preparing. */
    fun progress(request: DownloadRequest?, written: Long, queued: Int): Notification {
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (request == null) {
            return builder
                .setContentTitle(context.getString(R.string.browser_download_preparing))
                .setProgress(0, 0, true)
                .build()
        }
        val total = request.contentLength?.takeIf { it > 0 }
        val sizeText = if (total != null) {
            context.getString(R.string.browser_download_progress_of, Formatters.fileSize(written), Formatters.fileSize(total))
        } else {
            Formatters.fileSize(written)
        }
        val text = if (queued > 0) {
            context.resources.getQuantityString(R.plurals.browser_download_queued, queued, sizeText, queued)
        } else {
            sizeText
        }
        builder
            .setContentTitle(context.getString(R.string.browser_download_in_progress, request.fileName))
            .setContentText(text)
            .setVisibility(visibilityFor(request))
            .setPublicVersion(publicVersion(R.string.browser_download_in_progress_private, android.R.drawable.stat_sys_download))
            .addAction(0, context.getString(R.string.browser_download_cancel), DownloadService.cancelIntent(context, request.id))
        if (total != null) {
            builder.setProgress(PROGRESS_MAX, ((written * PROGRESS_MAX) / total).toInt().coerceIn(0, PROGRESS_MAX), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun showCompleted(request: DownloadRequest, contentUri: Uri) {
        ensureChannel()
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, request.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(
            context,
            request.id,
            view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(request.fileName)
            .setContentText(context.getString(R.string.browser_download_complete))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(visibilityFor(request))
            .setPublicVersion(publicVersion(R.string.browser_download_complete, android.R.drawable.stat_sys_download_done))
            .build()
        manager.notify(resultNotificationId(request), notification)
    }

    fun showFailed(request: DownloadRequest, reason: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(request.fileName)
            .setContentText(context.getString(R.string.browser_download_failed_reason, reason))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(visibilityFor(request))
            .setPublicVersion(publicVersion(R.string.browser_download_failed, android.R.drawable.stat_notify_error))
            .build()
        manager.notify(resultNotificationId(request), notification)
    }

    fun notifyProgress(notification: Notification) {
        manager.notify(PROGRESS_NOTIFICATION_ID, notification)
    }

    private fun visibilityFor(request: DownloadRequest): Int =
        if (request.isPrivate) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC

    /** What the lock screen shows instead of the file name for private downloads. */
    private fun publicVersion(titleRes: Int, icon: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(context.getString(titleRes))
            .build()

    private fun resultNotificationId(request: DownloadRequest): Int = RESULT_NOTIFICATION_BASE + request.id

    companion object {
        const val CHANNEL_ID = "browser_downloads"
        const val PROGRESS_NOTIFICATION_ID = 0x5D0001
        private const val RESULT_NOTIFICATION_BASE = 0x5D1000
        private const val PROGRESS_MAX = 1000
    }
}
