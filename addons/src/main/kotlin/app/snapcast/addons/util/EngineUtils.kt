package app.snapcast.addons.util

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import app.snapcast.browser.util.await
import kotlin.coroutines.cancellation.CancellationException
import org.mozilla.geckoview.Image

internal const val LOG_TAG = "SnapcastAddons"

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

/** Runs [block] now when called on the main thread, otherwise posts it there. */
internal fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
}

/** Posts [block] to the main thread, after the current engine callback has returned. */
internal fun postToMain(block: () -> Unit) {
    mainHandler.post { block() }
}

/** Decodes this image at [sizePx], or null when the extension has no usable icon. */
internal suspend fun Image.loadBitmap(sizePx: Int): Bitmap? = try {
    getBitmap(sizePx).await()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
