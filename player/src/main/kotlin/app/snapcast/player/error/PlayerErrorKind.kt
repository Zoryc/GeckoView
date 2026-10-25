package app.snapcast.player.error

import androidx.media3.common.PlaybackException

/** User-facing category of a playback failure; each maps to one readable message. */
internal enum class PlayerErrorKind {
    HTTP_FORBIDDEN,
    HTTP_NOT_FOUND,
    HTTP_ERROR,
    NETWORK,
    TIMEOUT,
    CLEARTEXT,
    NOT_A_VIDEO,
    FILE_NOT_FOUND,
    NO_PERMISSION,
    LOAD_FAILED,
    MALFORMED,
    UNSUPPORTED_FORMAT,
    DECODER,
    AUDIO_OUTPUT,
    DRM,
    BEHIND_LIVE_WINDOW,
    UNKNOWN,
    ;

    companion object {
        /**
         * Classifies a Media3 [errorCode] (`PlaybackException.ERROR_CODE_*`). A known [httpStatus] (from an
         * invalid-response-code cause) wins, because it explains the failure best.
         */
        fun classify(errorCode: Int, httpStatus: Int?): PlayerErrorKind {
            if (httpStatus != null) return fromHttpStatus(httpStatus)
            return when (errorCode) {
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> BEHIND_LIVE_WINDOW
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> HTTP_ERROR
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> NETWORK
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_TIMEOUT,
                -> TIMEOUT
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> CLEARTEXT
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> NOT_A_VIDEO
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> FILE_NOT_FOUND
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> NO_PERMISSION
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                -> MALFORMED
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                -> UNSUPPORTED_FORMAT
                else -> fromRange(errorCode)
            }
        }

        private fun fromHttpStatus(status: Int): PlayerErrorKind = when (status) {
            401, 403, 451 -> HTTP_FORBIDDEN
            404, 410 -> HTTP_NOT_FOUND
            else -> HTTP_ERROR
        }

        /** Media3 groups error codes by thousands: 2xxx I/O, 3xxx parsing, 4xxx decoding, 5xxx audio, 6xxx DRM. */
        private fun fromRange(errorCode: Int): PlayerErrorKind = when (errorCode / 1000) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED / 1000 -> LOAD_FAILED
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED / 1000 -> MALFORMED
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED / 1000 -> DECODER
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED / 1000 -> AUDIO_OUTPUT
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED / 1000 -> DRM
            else -> UNKNOWN
        }
    }
}
