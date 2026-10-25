package app.snapcast.browser.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Where a download is being written; [commit] publishes it, [abort] removes the partial file. */
internal interface DownloadTarget {
    val output: OutputStream

    /** Closes the stream and returns a content URI other apps can be granted read access to. */
    fun commit(): Uri

    fun abort()
}

/**
 * Public Downloads via MediaStore on Android 10+ (no storage permission needed). On Android 8-9
 * files go to the app-specific external Downloads directory (no permission either) and are shared
 * through [BrowserFileProvider]. All methods do disk IO: call them off the main thread.
 */
@Singleton
internal class DownloadStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun create(fileName: String, mimeType: String): DownloadTarget =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStoreTarget(fileName, mimeType) else fileTarget(fileName)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreTarget(fileName: String, mimeType: String): DownloadTarget {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the download")
        val stream = try {
            resolver.openOutputStream(uri) ?: throw IOException("No output stream for $uri")
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return object : DownloadTarget {
            override val output: OutputStream = stream

            override fun commit(): Uri {
                stream.close()
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, published, null, null)
                return uri
            }

            override fun abort() {
                runCatching { stream.close() }
                resolver.delete(uri, null, null)
            }
        }
    }

    private fun fileTarget(fileName: String): DownloadTarget {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, INTERNAL_DOWNLOADS_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val file = uniqueFile(dir, fileName)
        val stream = FileOutputStream(file)
        return object : DownloadTarget {
            override val output: OutputStream = stream

            override fun commit(): Uri {
                stream.close()
                return FileProvider.getUriForFile(context, BrowserFileProvider.authority(context), file)
            }

            override fun abort() {
                runCatching { stream.close() }
                file.delete()
            }
        }
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, DownloadFileNames.numbered(fileName, n++))
        }
        return candidate
    }

    private companion object {
        /** Must match res/xml/browser_file_paths.xml. */
        const val INTERNAL_DOWNLOADS_DIR = "downloads"
    }
}
