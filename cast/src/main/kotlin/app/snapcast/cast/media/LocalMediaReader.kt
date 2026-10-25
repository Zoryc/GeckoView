package app.snapcast.cast.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.snapcast.proxy.LocalSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A phone file ready to be served by the proxy. */
internal data class LocalFile(
    val source: LocalSource,
    val displayName: String?,
    val durationMs: Long?,
)

/** Reads user-picked content:// (or file://) URIs. Blocking: call on the IO dispatcher. */
@Singleton
internal class LocalMediaReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * Keeps read access to [uri] beyond the picking activity's lifetime when the provider allows it (the
     * proxy reads the file lazily, long after it was picked).
     */
    fun keepAccess(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not a persistable grant (e.g. ACTION_GET_CONTENT); the temporary grant still applies.
        }
    }

    /** Describes a video file; throws [IOException] when it cannot be opened. */
    @Throws(IOException::class)
    fun openVideo(uri: Uri, mimeTypeHint: String?): LocalFile {
        val (displayName, querySize) = query(uri)
        val descriptorLength = try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                ?: throw FileNotFoundException("Cannot open $uri")
        } catch (e: SecurityException) {
            throw IOException("No permission to read $uri", e)
        }
        val length = descriptorLength.takeIf { it >= 0 } ?: querySize
        val mimeType = CastMediaRules.localMimeType(
            fileName = displayName ?: uri.lastPathSegment,
            reportedMime = mimeTypeHint?.takeIf { it.isNotBlank() } ?: resolver.getType(uri),
        )
        return LocalFile(
            source = ContentUriSource(resolver, uri, mimeType, length),
            displayName = displayName,
            durationMs = durationMs(uri),
        )
    }

    /** Reads a small text file (subtitles); throws [IOException] when unreadable or larger than [maxBytes]. */
    @Throws(IOException::class)
    fun readBytes(uri: Uri, maxBytes: Int): ByteArray {
        val stream = try {
            resolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open $uri")
        } catch (e: SecurityException) {
            throw IOException("No permission to read $uri", e)
        }
        return stream.use { input ->
            val bytes = input.readNBytesCompat(maxBytes + 1)
            if (bytes.size > maxBytes) throw IOException("File too large")
            bytes
        }
    }

    /** Display name of [uri] as reported by its provider, if any. */
    fun displayName(uri: Uri): String? = query(uri).first ?: uri.lastPathSegment

    private fun query(uri: Uri): Pair<String?, Long?> = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size?.takeIf { it >= 0 }
            }
            ?: (null to null)
    } catch (e: RuntimeException) {
        null to null
    }

    private fun durationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (e: RuntimeException) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: IOException) {
                // Nothing to clean up further.
            } catch (e: RuntimeException) {
                // Nothing to clean up further.
            }
        }
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        var total = 0
        while (total < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - total))
            if (read < 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}

/**
 * [LocalSource] over a content URI. Each [open] gets a fresh descriptor. Whole files are positioned through
 * the file channel; sub-ranges of a file (asset descriptors with a start offset) are skipped through the
 * descriptor's own stream so its bounds stay respected.
 */
internal class ContentUriSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val mimeType: String,
    override val length: Long?,
) : LocalSource {

    @Throws(IOException::class)
    override fun open(offset: Long): InputStream {
        val descriptor = try {
            resolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException("Cannot open $uri")
        } catch (e: SecurityException) {
            throw IOException("No permission to read $uri", e)
        }
        val wholeFile = descriptor.startOffset == 0L && descriptor.declaredLength == AssetFileDescriptor.UNKNOWN_LENGTH
        val stream: FileInputStream = try {
            if (wholeFile) {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor.parcelFileDescriptor)
            } else {
                descriptor.createInputStream()
            }
        } catch (e: IOException) {
            descriptor.close()
            throw e
        }
        if (offset <= 0) return stream
        try {
            if (!wholeFile || !positionChannel(stream, offset)) skipFully(stream, offset)
        } catch (e: IOException) {
            stream.close()
            throw e
        }
        return stream
    }

    private fun positionChannel(stream: FileInputStream, offset: Long): Boolean = try {
        stream.channel.position(offset)
        true
    } catch (e: IOException) {
        false
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (stream.read() == -1) {
                throw EOFException("Offset $count is past the end of $uri")
            } else {
                remaining--
            }
        }
    }
}
