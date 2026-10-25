package app.snapcast.data.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Crash-safe file replacement: readers see either the old or the new content, never a partial write. */
internal object AtomicFiles {
    /**
     * Writes [text] (UTF-8) to a sibling temp file, syncs it to disk and renames it over [target].
     * Blocking; call from an IO dispatcher.
     */
    @Throws(IOException::class)
    fun writeText(target: File, text: String) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("No parent directory for $target")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val temp = File(dir, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    const val TEMP_SUFFIX = ".tmp"
}
