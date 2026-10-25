package app.snapcast.cast.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Downloads poster artwork for the media notification; caches the last image. Use from one thread. */
internal class ArtworkLoader(private val client: OkHttpClient) {
    private var cachedUrl: String? = null
    private var cachedBitmap: Bitmap? = null

    /** The cached image for [url], without loading. */
    fun cached(url: String?): Bitmap? = if (url != null && url == cachedUrl) cachedBitmap else null

    /** Loads [url] on the IO dispatcher (failures are cached as null so they are not retried). */
    suspend fun load(url: String): Bitmap? {
        if (url == cachedUrl) return cachedBitmap
        val bitmap = withContext(Dispatchers.IO) { fetch(url) }
        cachedUrl = url
        cachedBitmap = bitmap
        return bitmap
    }

    private fun fetch(url: String): Bitmap? = try {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.byteStream().use { readLimited(it) } ?: return null
            decodeSampled(bytes)
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_EDGE_PX || bounds.outHeight / (sampleSize * 2) >= MAX_EDGE_PX) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        const val MAX_EDGE_PX = 512
        const val BUFFER_SIZE = 16 * 1024
    }
}
