package app.snapcast.addons.install

import org.mozilla.geckoview.WebExtension.InstallException.ErrorCodes

/** User-facing categories of installation failures (each has a message in `strings.xml`). */
internal enum class InstallError {
    NETWORK,
    CORRUPT,
    FILE_ACCESS,
    NOT_SIGNED,
    INVALID_DOMAIN,
    BLOCKLISTED,
    SOFT_BLOCKED,
    INCOMPATIBLE,
    UNSUPPORTED_TYPE,
    ADMIN_ONLY,
    POSTPONED,
    UNSUPPORTED_SOURCE,
    UNKNOWN,
}

/** Maps `WebExtension.InstallException` error codes to [InstallError]s. */
internal object InstallErrors {
    private val SIGNATURE_HINT = Regex("not signed|unsigned|signature", RegexOption.IGNORE_CASE)

    /**
     * @param code `InstallException.code`, or null when the failure was not an `InstallException`
     * @param message the exception message, used when the code does not say enough
     * @return the error to show, or null when the user cancelled (not an error)
     */
    fun classify(code: Int?, message: String?): InstallError? = when (code) {
        ErrorCodes.ERROR_USER_CANCELED -> null
        ErrorCodes.ERROR_NETWORK_FAILURE -> InstallError.NETWORK
        ErrorCodes.ERROR_INCORRECT_HASH,
        ErrorCodes.ERROR_CORRUPT_FILE,
        ErrorCodes.ERROR_INCORRECT_ID,
        ErrorCodes.ERROR_UNEXPECTED_ADDON_VERSION,
        -> InstallError.CORRUPT
        ErrorCodes.ERROR_FILE_ACCESS -> InstallError.FILE_ACCESS
        ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED -> InstallError.NOT_SIGNED
        ErrorCodes.ERROR_UNEXPECTED_ADDON_TYPE,
        ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE,
        -> InstallError.UNSUPPORTED_TYPE
        ErrorCodes.ERROR_INVALID_DOMAIN -> InstallError.INVALID_DOMAIN
        ErrorCodes.ERROR_BLOCKLISTED -> InstallError.BLOCKLISTED
        ErrorCodes.ERROR_SOFT_BLOCKED -> InstallError.SOFT_BLOCKED
        ErrorCodes.ERROR_INCOMPATIBLE -> InstallError.INCOMPATIBLE
        ErrorCodes.ERROR_ADMIN_INSTALL_ONLY -> InstallError.ADMIN_ONLY
        ErrorCodes.ERROR_POSTPONED -> InstallError.POSTPONED
        else -> if (message != null && SIGNATURE_HINT.containsMatchIn(message)) InstallError.NOT_SIGNED else InstallError.UNKNOWN
    }
}
