package app.snapcast.browser.delegates

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.snapcast.browser.download.DownloadFileNames
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gecko's file prompt only accepts `file:` URIs reliably, while document pickers return `content:`
 * URIs. Picked documents are copied into the app cache (keeping their display names, which pages
 * see) and handed to Gecko as files. Copies older than a day are cleaned up. Call off the main thread.
 */
@Singleton
internal class UploadFileCopier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File get() = File(context.cacheDir, "browser_uploads")

    fun toFileUris(uris: List<Uri>): List<Uri> {
        deleteStaleCopies()
        return uris.mapNotNull { uri ->
            when (uri.scheme) {
                "file" -> uri
                "content" -> copy(uri)?.let { Uri.fromFile(it) }
                else -> null
            }
        }
    }

    private fun copy(uri: Uri): File? = try {
        val name = DownloadFileNames.sanitize(displayName(uri)) ?: DEFAULT_NAME
        val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(dir, name)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No stream for $uri")
        input.use { source -> target.outputStream().use { sink -> source.copyTo(sink) } }
        target
    } catch (e: IOException) {
        Log.w(TAG, "Could not read the picked file", e)
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "No permission to read the picked file", e)
        null
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        } ?: uri.lastPathSegment

    private fun deleteStaleCopies() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val TAG = "SnapcastBrowser"
        const val DEFAULT_NAME = "upload"
    }
}
