package app.snapcast.player.error

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerErrorKindTest {

    @Test
    fun httpStatusWinsAndIsGroupedByMeaning() {
        val code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertThat(PlayerErrorKind.classify(code, 403)).isEqualTo(PlayerErrorKind.HTTP_FORBIDDEN)
        assertThat(PlayerErrorKind.classify(code, 401)).isEqualTo(PlayerErrorKind.HTTP_FORBIDDEN)
        assertThat(PlayerErrorKind.classify(code, 404)).isEqualTo(PlayerErrorKind.HTTP_NOT_FOUND)
        assertThat(PlayerErrorKind.classify(code, 410)).isEqualTo(PlayerErrorKind.HTTP_NOT_FOUND)
        assertThat(PlayerErrorKind.classify(code, 500)).isEqualTo(PlayerErrorKind.HTTP_ERROR)
        assertThat(PlayerErrorKind.classify(code, null)).isEqualTo(PlayerErrorKind.HTTP_ERROR)
        // A status found in the cause chain explains other codes too.
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, 403))
            .isEqualTo(PlayerErrorKind.HTTP_FORBIDDEN)
    }

    @Test
    fun networkErrors() {
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
            .isEqualTo(PlayerErrorKind.NETWORK)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
            .isEqualTo(PlayerErrorKind.TIMEOUT)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_TIMEOUT, null)).isEqualTo(PlayerErrorKind.TIMEOUT)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, null))
            .isEqualTo(PlayerErrorKind.CLEARTEXT)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, null))
            .isEqualTo(PlayerErrorKind.NOT_A_VIDEO)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, null))
            .isEqualTo(PlayerErrorKind.LOAD_FAILED)
    }

    @Test
    fun localFileErrors() {
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null))
            .isEqualTo(PlayerErrorKind.FILE_NOT_FOUND)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, null))
            .isEqualTo(PlayerErrorKind.NO_PERMISSION)
    }

    @Test
    fun formatErrors() {
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, null))
            .isEqualTo(PlayerErrorKind.MALFORMED)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
            .isEqualTo(PlayerErrorKind.MALFORMED)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null))
            .isEqualTo(PlayerErrorKind.UNSUPPORTED_FORMAT)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED, null))
            .isEqualTo(PlayerErrorKind.UNSUPPORTED_FORMAT)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, null))
            .isEqualTo(PlayerErrorKind.UNSUPPORTED_FORMAT)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, null))
            .isEqualTo(PlayerErrorKind.DECODER)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null))
            .isEqualTo(PlayerErrorKind.DECODER)
    }

    @Test
    fun playbackErrors() {
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, null))
            .isEqualTo(PlayerErrorKind.BEHIND_LIVE_WINDOW)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, null))
            .isEqualTo(PlayerErrorKind.AUDIO_OUTPUT)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, null))
            .isEqualTo(PlayerErrorKind.DRM)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED, null)).isEqualTo(PlayerErrorKind.DRM)
    }

    @Test
    fun anythingElseIsUnknown() {
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_UNSPECIFIED, null)).isEqualTo(PlayerErrorKind.UNKNOWN)
        assertThat(PlayerErrorKind.classify(PlaybackException.ERROR_CODE_REMOTE_ERROR, null)).isEqualTo(PlayerErrorKind.UNKNOWN)
        assertThat(PlayerErrorKind.classify(-2, null)).isEqualTo(PlayerErrorKind.UNKNOWN)
        assertThat(PlayerErrorKind.classify(7001, null)).isEqualTo(PlayerErrorKind.UNKNOWN)
    }
}
