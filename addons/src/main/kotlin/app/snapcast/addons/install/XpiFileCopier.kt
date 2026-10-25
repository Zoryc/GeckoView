package app.snapcast.addons.install

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * Copies add-on packages picked from a content:// URI into the app cache, because GeckoView can only
 * install from https, file: and resource: URIs. All methods block: call them on `Dispatchers.IO`.
 */
internal class XpiFileCopier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val directory: File get() = File(context.cacheDir, DIRECTORY)

    /** Copies [contentUri] into a new cache file and returns it; the caller deletes it after installing. */
    fun copyToCache(contentUri: String): File {
        val dir = directory
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val target = File.createTempFile(PREFIX, SUFFIX, dir)
        try {
            val input = context.contentResolver.openInputStream(Uri.parse(contentUri))
                ?: throw IOException("No content for $contentUri")
            input.use { source ->
                FileOutputStream(target).use { sink -> BoundedCopy.copy(source, sink, MAX_BYTES) }
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        } catch (e: SecurityException) {
            target.delete()
            throw IOException("No permission to read $contentUri", e)
        }
        return target
    }

    /** Removes copies left behind by an install that was interrupted (e.g. the app was killed). */
    fun clearCache() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val DIRECTORY = "addon-installs"
        const val PREFIX = "install-"
        const val SUFFIX = ".xpi"

        /** Far above any real add-on (uBlock Origin is ~4 MB); stops a wrong pick from filling the disk. */
        const val MAX_BYTES = 200L * 1024 * 1024
    }
}

/** Stream copy with an upper size bound. */
internal object BoundedCopy {
    private const val BUFFER_SIZE = 64 * 1024

    /** Copies [input] to [output]; throws [IOException] once more than [maxBytes] were read. Returns the byte count. */
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > maxBytes) throw IOException("File is larger than $maxBytes bytes")
            output.write(buffer, 0, read)
        }
    }
}
