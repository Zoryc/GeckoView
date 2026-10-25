package app.snapcast.browser.download

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import app.snapcast.browser.BrowserEvent
import app.snapcast.browser.R
import app.snapcast.browser.hooks.BrowserEventBus
import app.snapcast.data.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.WebResponse

/** A response body waiting to be written to Downloads. */
internal class DownloadRequest(
    val id: Int,
    val response: WebResponse,
    val fileName: String,
    val mimeType: String,
    val contentLength: Long?,
    /** From a private tab: the file is kept, but notifications hide its name on the lock screen. */
    val isPrivate: Boolean,
)

/**
 * In-process queue of downloads, fed by `ContentDelegate.onExternalResponse` and drained by
 * [DownloadService]. The response bodies are live streams, so the queue never leaves the process.
 */
@Singleton
internal class DownloadQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: BrowserEventBus,
    private val processor: dagger.Lazy<DownloadProcessor>,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val pending = ArrayDeque<DownloadRequest>()
    private val ids = AtomicInteger(1)

    val size: Int get() = synchronized(pending) { pending.size }

    fun enqueue(response: WebResponse, isPrivate: Boolean) {
        val contentType = response.headers[HEADER_CONTENT_TYPE]
        val fileName = fileNameFor(response.uri, response.headers[HEADER_CONTENT_DISPOSITION], contentType)
        if (response.body == null) {
            events.emit(BrowserEvent.DownloadFailed(fileName, context.getString(R.string.browser_download_error_empty)))
            return
        }
        val request = DownloadRequest(
            id = ids.getAndIncrement(),
            response = response,
            fileName = fileName,
            mimeType = mimeTypeFor(fileName, contentType),
            contentLength = DownloadFileNames.contentLengthOf(response.headers[HEADER_CONTENT_LENGTH]),
            isPrivate = isPrivate,
        )
        synchronized(pending) { pending.addLast(request) }
        events.emit(BrowserEvent.DownloadStarted(fileName))
        startProcessing()
    }

    fun poll(): DownloadRequest? = synchronized(pending) { pending.removeFirstOrNull() }

    private fun startProcessing() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        } catch (e: IllegalStateException) {
            // Foreground services cannot start while the app is in the background (Android 12+);
            // finish the work in-process without the progress notification instead.
            Log.w(TAG, "Download service unavailable, downloading in-process", e)
            appScope.launch(Dispatchers.Main.immediate) { processor.get().drain(listener = null) }
        }
    }

    private fun fileNameFor(url: String, contentDisposition: String?, contentType: String?): String {
        val mime = DownloadFileNames.mimeTypeOf(contentType)
        val fromHeader = DownloadFileNames.fromContentDisposition(contentDisposition)
        val name = fromHeader ?: DownloadFileNames.sanitize(URLUtil.guessFileName(url, null, mime))
        return name ?: context.getString(R.string.browser_download_default_name)
    }

    /** Prefer the type implied by the extension so MediaStore keeps the file name as is. */
    private fun mimeTypeFor(fileName: String, contentType: String?): String {
        val fromExtension = DownloadFileNames.extensionOf(fileName)?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        return fromExtension ?: DownloadFileNames.mimeTypeOf(contentType) ?: DEFAULT_MIME_TYPE
    }

    private companion object {
        const val TAG = "SnapcastBrowser"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_CONTENT_DISPOSITION = "Content-Disposition"
        const val HEADER_CONTENT_LENGTH = "Content-Length"
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
    }
}
