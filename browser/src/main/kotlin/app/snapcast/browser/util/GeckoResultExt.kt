package app.snapcast.browser.util

import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoResult

/**
 * Suspends until this [GeckoResult] completes and returns its value, or throws the exception it was
 * completed with.
 *
 * Listeners run on the result's own looper; results created on a thread without a looper are
 * re-dispatched to the main looper so this works for every result GeckoView hands out.
 * Cancelling the coroutine stops waiting but does not cancel the underlying engine operation.
 */
suspend fun <T> GeckoResult<T>.await(): T? = suspendCancellableCoroutine { continuation ->
    val dispatching = if (looper != null) this else withHandler(Handler(Looper.getMainLooper()))
    dispatching.accept(
        { value ->
            if (continuation.isActive) continuation.resume(value)
        },
        { error ->
            if (continuation.isActive) {
                continuation.resumeWithException(error ?: IllegalStateException("GeckoResult failed without an exception"))
            }
        },
    )
}
