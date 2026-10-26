package app.snapcast.player.error

import android.content.res.Resources
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import app.snapcast.player.R

/** What the error overlay shows: a readable [message] and the Media3 error code name as [detail]. */
internal data class PlayerErrorUi(
    val kind: PlayerErrorKind,
    val message: String,
    val detail: String,
    /** The link was refused or is gone and the page it came from is known: offer to reload it and play again. */
    val canReloadPage: Boolean = false,
    /** HTTP status of the failed request and the host it went to, when the failure was an HTTP error. */
    val httpStatus: Int? = null,
    val failedHost: String? = null,
) {
    /** Retrying cannot help with protected content or a format the device's player doesn't handle. */
    val canRetry: Boolean
        get() = kind != PlayerErrorKind.DRM && kind != PlayerErrorKind.UNSUPPORTED_FORMAT
}

/** Turns Media3 playback errors into messages people can act on. */
internal object PlaybackErrorMessages {

    fun describe(resources: Resources, error: PlaybackException): PlayerErrorUi {
        val http = httpErrorOf(error)
        val status = http?.responseCode
        val kind = PlayerErrorKind.classify(error.errorCode, status)
        val message = when (kind) {
            PlayerErrorKind.HTTP_FORBIDDEN -> resources.getString(R.string.player_error_http_forbidden, status)
            PlayerErrorKind.HTTP_NOT_FOUND -> resources.getString(R.string.player_error_http_not_found, status)
            PlayerErrorKind.HTTP_ERROR -> if (status != null) {
                resources.getString(R.string.player_error_http_status, status)
            } else {
                resources.getString(R.string.player_error_http)
            }
            PlayerErrorKind.NETWORK -> resources.getString(R.string.player_error_network)
            PlayerErrorKind.TIMEOUT -> resources.getString(R.string.player_error_timeout)
            PlayerErrorKind.CLEARTEXT -> resources.getString(R.string.player_error_cleartext)
            PlayerErrorKind.NOT_A_VIDEO -> resources.getString(R.string.player_error_not_a_video)
            PlayerErrorKind.FILE_NOT_FOUND -> resources.getString(R.string.player_error_file_not_found)
            PlayerErrorKind.NO_PERMISSION -> resources.getString(R.string.player_error_no_permission)
            PlayerErrorKind.LOAD_FAILED -> resources.getString(R.string.player_error_load_failed)
            PlayerErrorKind.MALFORMED -> resources.getString(R.string.player_error_malformed)
            PlayerErrorKind.UNSUPPORTED_FORMAT -> resources.getString(R.string.player_error_unsupported)
            PlayerErrorKind.DECODER -> resources.getString(R.string.player_error_decoder)
            PlayerErrorKind.AUDIO_OUTPUT -> resources.getString(R.string.player_error_audio_output)
            PlayerErrorKind.DRM -> resources.getString(R.string.player_error_drm)
            PlayerErrorKind.BEHIND_LIVE_WINDOW -> resources.getString(R.string.player_error_live_window)
            PlayerErrorKind.UNKNOWN -> resources.getString(R.string.player_error_unknown)
        }
        return PlayerErrorUi(
            kind = kind,
            message = message,
            detail = error.errorCodeName,
            httpStatus = status,
            failedHost = http?.dataSpec?.uri?.host,
        )
    }

    /** The first invalid-response-code exception in the cause chain, if any. */
    private fun httpErrorOf(error: Throwable): HttpDataSource.InvalidResponseCodeException? {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause
            cause = cause.cause
            depth++
        }
        return null
    }

    private const val MAX_CAUSE_DEPTH = 8
}
