package app.snapcast.cast.chromecast

import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A receiver request that failed or timed out. */
internal class CastRequestException(message: String) : Exception(message)

/** Generous upper bound; the SDK normally reports failures itself well before. */
private const val REQUEST_TIMEOUT_MS = 45_000L

/**
 * Waits for a media-channel request. Must be called on the main thread (the SDK delivers the callback on
 * the thread's looper).
 */
internal suspend fun PendingResult<RemoteMediaClient.MediaChannelResult>.awaitResult(): Result<Unit> {
    val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        suspendCancellableCoroutine<RemoteMediaClient.MediaChannelResult> { continuation ->
            setResultCallback { channelResult -> if (continuation.isActive) continuation.resume(channelResult) }
        }
    } ?: return Result.failure(CastRequestException("The receiver did not answer"))
    val status = result.status
    return if (status.isSuccess) {
        Result.success(Unit)
    } else {
        Result.failure(CastRequestException("Receiver request failed (${status.statusCode}): ${status.statusMessage}"))
    }
}
