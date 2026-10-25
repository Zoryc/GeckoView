package app.snapcast.addons.install

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mozilla.geckoview.WebExtension.InstallException.ErrorCodes

class InstallErrorsTest {

    @Test
    fun `user cancel is not an error`() {
        assertThat(InstallErrors.classify(ErrorCodes.ERROR_USER_CANCELED, null)).isNull()
    }

    @Test
    fun `error codes map to categories`() {
        val expected = mapOf(
            ErrorCodes.ERROR_NETWORK_FAILURE to InstallError.NETWORK,
            ErrorCodes.ERROR_INCORRECT_HASH to InstallError.CORRUPT,
            ErrorCodes.ERROR_CORRUPT_FILE to InstallError.CORRUPT,
            ErrorCodes.ERROR_INCORRECT_ID to InstallError.CORRUPT,
            ErrorCodes.ERROR_UNEXPECTED_ADDON_VERSION to InstallError.CORRUPT,
            ErrorCodes.ERROR_FILE_ACCESS to InstallError.FILE_ACCESS,
            ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED to InstallError.NOT_SIGNED,
            ErrorCodes.ERROR_UNEXPECTED_ADDON_TYPE to InstallError.UNSUPPORTED_TYPE,
            ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE to InstallError.UNSUPPORTED_TYPE,
            ErrorCodes.ERROR_INVALID_DOMAIN to InstallError.INVALID_DOMAIN,
            ErrorCodes.ERROR_BLOCKLISTED to InstallError.BLOCKLISTED,
            ErrorCodes.ERROR_SOFT_BLOCKED to InstallError.SOFT_BLOCKED,
            ErrorCodes.ERROR_INCOMPATIBLE to InstallError.INCOMPATIBLE,
            ErrorCodes.ERROR_ADMIN_INSTALL_ONLY to InstallError.ADMIN_ONLY,
            ErrorCodes.ERROR_POSTPONED to InstallError.POSTPONED,
        )
        expected.forEach { (code, error) ->
            assertThat(InstallErrors.classify(code, null)).isEqualTo(error)
        }
    }

    @Test
    fun `signature problems are recognised from the message`() {
        assertThat(InstallErrors.classify(null, "Add-on is not signed")).isEqualTo(InstallError.NOT_SIGNED)
        assertThat(InstallErrors.classify(12345, "Invalid signature")).isEqualTo(InstallError.NOT_SIGNED)
    }

    @Test
    fun `anything else is unknown`() {
        assertThat(InstallErrors.classify(null, null)).isEqualTo(InstallError.UNKNOWN)
        assertThat(InstallErrors.classify(12345, "boom")).isEqualTo(InstallError.UNKNOWN)
    }
}
