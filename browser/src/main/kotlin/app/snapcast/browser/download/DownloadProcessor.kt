package app.snapcast.browser.download

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.snapcast.browser.BrowserEvent
import app.snapcast.browser.R
import app.snapcast.browser.hooks.BrowserEventBus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes queued downloads one after another. Driven by [DownloadService] (or in-process when the
 * service cannot start); [drain] and [cancel] are called on the main thread.
 */
@Singleton
internal class DownloadProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queue: DownloadQueue,
    private val storage: DownloadStorage,
    private val notifications: DownloadNotifications,
    private val events: BrowserEventBus,
) {
    /** Progress of the running download; called on the main thread, throttled. */
    fun interface ProgressListener {
        fun onProgress(request: DownloadRequest, written: Long)
    }

    private var draining = false
    private var currentId: Int? = null
    private var currentJob: Job? = null

    /** Processes the queue until it is empty. A second concurrent call returns immediately. */
    suspend fun drain(listener: ProgressListener?) {
        if (draining) return
        draining = true
        try {
            coroutineScope {
                while (true) {
                    val request = queue.poll() ?: break
                    listener?.onProgress(request, 0L)
                    val job = launch { download(request, listener) }
                    currentId = request.id
                    currentJob = job
                    job.join()
                    currentId = null
                    currentJob = null
                }
            }
        } finally {
            draining = false
        }
    }

    /** Cancels the download with [id] if it is the one running. */
    fun cancel(id: Int) {
        if (currentId == id) currentJob?.cancel()
    }

    private suspend fun download(request: DownloadRequest, listener: ProgressListener?) {
        var target: DownloadTarget? = null
        try {
            val uri = withContext(Dispatchers.IO) {
                val body = request.response.body ?: throw IOException("Response has no body")
                val output = storage.create(request.fileName, request.mimeType).also { target = it }
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                var lastReport = SystemClock.elapsedRealtime()
                body.use { input ->
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.output.write(buffer, 0, read)
                        written += read
                        val now = SystemClock.elapsedRealtime()
                        if (listener != null && now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            val progress = written
                            withContext(Dispatchers.Main) { listener.onProgress(request, progress) }
                        }
                    }
                }
                output.commit()
            }
            notifications.showCompleted(request, uri)
            events.emit(BrowserEvent.DownloadFinished(request.fileName, uri.toString()))
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                target?.abort()
                runCatching { request.response.body?.close() }
            }
            events.emit(BrowserEvent.DownloadFailed(request.fileName, context.getString(R.string.browser_download_cancelled)))
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download of ${request.fileName} failed", e)
            withContext(NonCancellable + Dispatchers.IO) {
                target?.abort()
                // The target may have failed before body.use {} started: the response must still be
                // closed, or its Gecko channel keeps the connection open (closing twice is harmless).
                runCatching { request.response.body?.close() }
            }
            val reason = context.getString(R.string.browser_download_error_io)
            notifications.showFailed(request, reason)
            events.emit(BrowserEvent.DownloadFailed(request.fileName, reason))
        }
    }

    private companion object {
        const val TAG = "SnapcastBrowser"
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
