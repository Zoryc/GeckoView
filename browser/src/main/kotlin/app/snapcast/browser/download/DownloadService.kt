package app.snapcast.browser.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that keeps the process alive while [DownloadQueue] is
 * drained, showing a progress notification with a cancel action. Stops itself when the queue is
 * empty.
 */
@AndroidEntryPoint
internal class DownloadService : Service() {
    @Inject
    lateinit var processor: DownloadProcessor

    @Inject
    lateinit var queue: DownloadQueue

    @Inject
    lateinit var notifications: DownloadNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var drainJob: Job? = null
    private var lastStartId = 0
    private var lastNotification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every start must promote the service, including the cancel action's.
        startInForeground(lastNotification ?: notifications.progress(null, 0L, queue.size))
        if (intent?.action == ACTION_CANCEL) {
            processor.cancel(intent.getIntExtra(EXTRA_DOWNLOAD_ID, -1))
        }
        if (drainJob?.isActive != true) {
            drainJob = scope.launch {
                processor.drain { request, written ->
                    val notification = notifications.progress(request, written, queue.size)
                    lastNotification = notification
                    notifications.notifyProgress(notification)
                }
                lastNotification = null
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                // Only stops when no newer start request is pending; otherwise that request drains again.
                stopSelfResult(lastStartId)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits dataSync services; stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, DownloadNotifications.PROGRESS_NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val ACTION_CANCEL = "app.snapcast.browser.download.CANCEL"
        private const val EXTRA_DOWNLOAD_ID = "download_id"

        /** Notification action that cancels the running download [downloadId]. */
        fun cancelIntent(context: Context, downloadId: Int): PendingIntent = PendingIntent.getService(
            context,
            downloadId,
            Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_DOWNLOAD_ID, downloadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
